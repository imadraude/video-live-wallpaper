package io.github.codeg0blin.videowallpaper

import android.content.SharedPreferences
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.preference.PreferenceManager
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders the user's chosen video as a looping live wallpaper.
 *
 * Android creates a new Engine instance per surface that needs the wallpaper
 * (home screen, lock screen, or both — depending on OEM/launcher behavior),
 * so each Engine owns its own MediaPlayer to avoid cross-talk between them.
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var mediaPlayer: MediaPlayer? = null
        // A preparing MediaPlayer must not receive start/pause on unlock.
        private var playerPrepared = false
        // A retained decoder has no video destination until the replacement
        // EGL/native Surface is ready. Never resume it in this interval.
        private var awaitingVideoSurface = false
        // Keep calls into MediaPlayer off the render thread: its native getters
        // can block during codec transitions (notably while unlocking).
        @Volatile private var videoWidth = 0
        @Volatile private var videoHeight = 0

        // Brief visibility changes during unlocking shouldn't repeatedly pause
        // and resume the hardware decoder.
        private val playbackHandler = Handler(Looper.getMainLooper())
        private val pausePlaybackRunnable = Runnable {
            if (!visible && currentHolder != null) {
                mediaPlayer?.let { player ->
                    val speed = prefs?.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_SPEED) ?: DEFAULT_SPEED
                    applySpeed(player, speed)
                }
            }
        }
        // Keep the already prepared codec only across quick wallpaper surface
        // recreation. The timeout avoids retaining a hardware decoder when
        // Android has permanently removed the wallpaper surface.
        private val releaseDetachedPlayerRunnable = Runnable {
            if (currentHolder == null) {
                Log.d(TAG, "Detached surface grace expired; releasing idle video decoder")
                releasePlayer()
            }
        }

        // Coalesce frame callbacks; never queue unlimited GL draws on unlock.
        private val frameDrawQueued = AtomicBoolean(false)
        private var prefs: SharedPreferences? = null
        // @Volatile because the GIF path reads it on the render thread.
        @Volatile
        private var visible = false

        // Counts consecutive prepare attempts that ended in a "transient"
        // error in a row, so a genuinely broken source (not just a one-off
        // state-machine hiccup) can't retry forever and spin the CPU.
        private var consecutiveErrorRetries = 0

        // Tracks whether *we* believe the player is currently started, kept
        // independently of MediaPlayer.isPlaying(). On some OEM MediaPlayer
        // implementations (observed here) isPlaying() can lag behind the
        // actual native state transition by a short window, so relying on it
        // alone to guard against a duplicate start() call isn't reliable —
        // two onVisibilityChanged(true) calls landing close together (a known
        // quirk during unlock animations on some launchers) could both see
        // isPlaying()==false and both call start(), producing error -38.
        private var weBelieveStarted = false

        // The playback speed we last pushed to the current MediaPlayer. A new
        // player always starts at 1.0, so this resets whenever the player is
        // released. Tracking it (rather than skipping the call whenever the
        // requested speed is 1.0) is what lets the slider return to normal
        // speed after being moved, without touching playbackParams needlessly.
        private var appliedSpeed = 1.0f

        // Kept so the pref listener can trigger a re-prepare with the
        // correct surface without waiting for the next onSurfaceCreated.
        private var currentHolder: SurfaceHolder? = null

        // Owns the EGL/GL pipeline that draws video frames with real Fill/Fit
        // crop-or-letterbox math (see VideoFrameRenderer) — replaces the old
        // direct MediaPlayer-to-Surface path, since setVideoScalingMode() is
        // a no-op on a WallpaperService Engine's surface (issue #1).
        @Volatile private var renderer: VideoFrameRenderer? = null

        // All renderer/EGL calls (start, drawFrame, release) must happen on
        // this single thread — EGL contexts are thread-confined. Created in
        // onSurfaceCreated, quit in onSurfaceDestroyed.
        private var renderThread: HandlerThread? = null
        @Volatile private var renderHandler: Handler? = null

        // GIF playback (issue #2). gifPlayer is only touched on the render
        // thread; everything that changes it is posted to renderHandler.
        // prepareGeneration is bumped by every releasePlayer() so a GIF load
        // that is still in flight when the selection changes (or the surface
        // goes away) discards its result instead of starting stale playback.
        private var gifPlayer: GifPlayer? = null

        @Volatile
        private var prepareGeneration = 0

        // Draws exactly one frame using the current holder/player state.
        // Posted either by VideoFrameRenderer's onFrameAvailableListener
        // (normal case — a new decoded frame is ready) or directly by
        // startDrawLoopIfVisible() for a one-shot "redraw the last frame"
        // kick after a resize or becoming visible again, since a resize
        // alone doesn't produce a new video frame but does need a redraw
        // at the new viewport size.
        //
        // Deliberately NOT a fixed-rate timer: drawing unconditionally at
        // ~60fps regardless of whether a new frame had arrived wasted GPU
        // work on every redundant redraw of an unchanged frame, which adds
        // up for a continuously-running live wallpaper. Driving off actual
        // frame arrival means we draw only as often as the video itself
        // produces new frames.
        private val drawFrameRunnable = Runnable {
            frameDrawQueued.set(false)
            if (!visible) return@Runnable
            val r = renderer ?: return@Runnable
            val holder = currentHolder ?: return@Runnable
            if (gifPlayer != null) {
                // GIF active: redraw the last uploaded frame (resize, becoming
                // visible, Fill/Fit change). New frames are driven by GifPlayer.
                r.drawGifFrame(holder.surfaceFrame.width(), holder.surfaceFrame.height())
                return@Runnable
            }
            r.drawFrame(
                holder.surfaceFrame.width(),
                holder.surfaceFrame.height(),
                videoWidth,
                videoHeight
            )
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs = PreferenceManager.getDefaultSharedPreferences(this@VideoWallpaperService)
            prefs?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            currentHolder = holder
            playbackHandler.removeCallbacks(releaseDetachedPlayerRunnable)
            consecutiveErrorRetries = 0
            configureOutput(holder)
        }

        /**
         * Direct output is used only when the selected settings can be
         * displayed without modifying the aspect ratio. Native wallpaper
         * surfaces may ignore MediaPlayer's Fit scaling hint, so Fit always
         * uses the existing OpenGL renderer for proper black letterbox bars.
         * GIFs also always require the OpenGL renderer.
         */
        private fun configureOutput(holder: SurfaceHolder) {
            if (currentHolder !== holder) return
            if (!needsGlRenderer()) {
                Log.i(TAG, "Efficient Fill mode: native video surface, no OpenGL renderer")
                attachOrPreparePlayer(holder)
                return
            }

            val thread = HandlerThread("VideoFrameRenderer").also { it.start() }
            renderThread = thread
            val handler = Handler(thread.looper)
            renderHandler = handler
            handler.post {
                // Surface teardown can outrun asynchronous EGL initialization.
                // Never create a renderer for an already destroyed wallpaper.
                if (currentHolder !== holder || renderHandler !== handler) return@post
                val r = VideoFrameRenderer()
                val started = r.start(holder.surface)
                if (currentHolder !== holder || renderHandler !== handler) {
                    r.release()
                    return@post
                }
                if (started) {
                    renderer = r
                    applyScalingModePref()
                    // Draw exactly when a new frame arrives, rather than
                    // polling on a fixed timer. The listener itself may
                    // fire on an arbitrary thread (per SurfaceTexture's
                    // docs), so hop back onto our own render thread via
                    // renderHandler before touching the renderer/GL state.
                    r.onFrameAvailableListener = {
                        if (renderHandler === handler && visible) {
                            // SurfaceTexture delivers this callback on the
                            // renderer's HandlerThread. Drawing here avoids
                            // one extra Handler.post() hop per video frame.
                            handler.removeCallbacks(drawFrameRunnable)
                            frameDrawQueued.set(false)
                            drawFrameRunnable.run()
                        }
                    }
                } else {
                    Log.e(TAG, "VideoFrameRenderer failed to initialize — falling back to no crop/fit support")
                }
                // preparePlayer() reads renderer.getVideoInputSurface(), so
                // it must run after renderer is set (or confirmed failed)
                // above — not just "hopefully after", since this whole
                // block runs on the render thread while preparePlayer()
                // touches MediaPlayer, which needs to stay on the main
                // thread. Post back to main rather than calling directly.
                playbackHandler.post {
                    // Do not resurrect the player if the surface is gone.
                    if (currentHolder === holder && renderHandler === handler) {
                        attachOrPreparePlayer(holder)
                    }
                }
            }
        }

        /**
         * Re-bind a prepared decoder to the replacement output Surface.
         * MediaPlayer.setSurface supports hot-swapping a video destination,
         * so a brief home/lock wallpaper surface recreation doesn't require
         * opening the video again or running prepareAsync again.
         */
        private fun attachOrPreparePlayer(holder: SurfaceHolder) {
            playbackHandler.removeCallbacks(releaseDetachedPlayerRunnable)
            val retained = mediaPlayer
            if (retained != null && PlaybackReusePolicy.canReuseVideoDecoder(playerPrepared, isSelectedGif())) {
                try {
                    val surface = renderer?.getVideoInputSurface() ?: holder.surface
                    retained.setSurface(surface)
                    awaitingVideoSurface = false
                    Log.d(TAG, "Reused prepared MediaPlayer after wallpaper surface recreation")
                    if (renderer == null) applyNativeScaling(retained)
                    if (visible) {
                        val speed = prefs?.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_SPEED)
                            ?: DEFAULT_SPEED
                        applySpeed(retained, speed)
                    }
                    startDrawLoopIfVisible()
                    return
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Reattaching decoder failed; preparing fresh player", e)
                }
            }
            preparePlayer(holder)
        }

        private fun isSelectedGif(): Boolean {
            val preferences = prefs ?: return false
            // Cache the file header once, not at every screen unlock.
            if (preferences.contains(PREF_SOURCE_IS_GIF)) {
                return preferences.getBoolean(PREF_SOURCE_IS_GIF, false)
            }
            val uri = preferences.getString(PREF_VIDEO_URI, null)?.let { Uri.parse(it) }
                ?: return false
            return GifFiles.looksLikeGif(applicationContext.contentResolver, uri).also {
                preferences.edit().putBoolean(PREF_SOURCE_IS_GIF, it).apply()
            }
        }

        private fun needsGlRenderer(): Boolean {
            val mode = prefs?.getInt(PREF_RENDER_MODE, DEFAULT_RENDER_MODE)
                ?: DEFAULT_RENDER_MODE
            val fit = prefs?.getInt(PREF_SCALING_MODE, DEFAULT_SCALING_MODE) ==
                MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT
            // Compatible and Fit always use GL. Avoid sniffing the video
            // header unless native output would otherwise be possible.
            if (mode != RENDER_MODE_EFFICIENT || fit) return true
            return RenderModePolicy.requiresOpenGl(mode, isSelectedGif(), fit = false)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            playbackHandler.removeCallbacks(pausePlaybackRunnable)
            if (isVisible) {
                startDrawLoopIfVisible()
            } else {
                stopDrawLoop()
            }
            renderHandler?.post {
                if (isVisible) gifPlayer?.start() else gifPlayer?.pause()
            }
            val player = mediaPlayer ?: return
            if (currentHolder == null || awaitingVideoSurface) return
            // Route through applySpeed rather than calling start()/pause()
            // directly here. Having two independent call sites (this one and
            // the one inside preparePlayer's onPreparedListener) both touching
            // start()/pause() on the same MediaPlayer was racy — if a visibility
            // change and a prepare-completion landed close together, they could
            // stack an extra start() on top of one that was still resolving,
            // which some OEM MediaPlayer implementations (observed on
            // Adreno/TCL) turn into error -38. Funneling everything through
            // applySpeed keeps state transitions to one guarded path.
            if (isVisible) {
                if (playerPrepared) {
                    val speed = prefs?.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_SPEED) ?: DEFAULT_SPEED
                    applySpeed(player, speed)
                }
            } else if (playerPrepared) {
                playbackHandler.postDelayed(pausePlaybackRunnable, VISIBILITY_PAUSE_DELAY_MS)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            currentHolder = holder
            // Resize alone doesn't produce a new video frame, but the
            // viewport dimensions changed, so geometry needs recomputing
            // (handled inside drawFrame's cache-invalidation check) and
            // the screen needs a redraw at the new size.
            startDrawLoopIfVisible()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            currentHolder = null
            visible = false
            playbackHandler.removeCallbacks(pausePlaybackRunnable)
            // Keep a prepared video decoder alive briefly for the next
            // Surface. The EGL output still must be destroyed because its
            // window Surface is invalid after this callback.
            detachOrReleasePlayer()
            releaseRenderer()
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
            releasePlayer()
            releaseRenderer()
        }

        /**
         * Pause and detach the output surface without tearing down the video
         * codec. The same Engine can receive a new Surface shortly after
         * a lock-screen transition. Never retain GIF playback or players
         * still in the asynchronous preparation state.
         */
        private fun detachOrReleasePlayer() {
            val player = mediaPlayer
            if (player == null || !PlaybackReusePolicy.canReuseVideoDecoder(playerPrepared, isSelectedGif())) {
                releasePlayer()
                return
            }
            try {
                if (weBelieveStarted) {
                    player.pause()
                    weBelieveStarted = false
                }
                player.setSurface(null)
                awaitingVideoSurface = true
                playbackHandler.removeCallbacks(releaseDetachedPlayerRunnable)
                playbackHandler.postDelayed(
                    releaseDetachedPlayerRunnable, SURFACE_REUSE_GRACE_MS
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot detach video surface; releasing decoder", e)
                releasePlayer()
            }
        }

        /**
         * Stops the draw loop, releases the renderer's EGL/GL resources (on
         * the render thread, since EGL calls are thread-confined), then
         * quits the render thread itself. Safe to call even if nothing was
         * ever successfully initialized.
         */
        private fun releaseRenderer() {
            stopDrawLoop()
            val handler = renderHandler
            val r = renderer
            renderer = null
            if (handler != null && r != null) {
                handler.post { r.release() }
            }
            renderThread?.quitSafely()
            renderThread = null
            renderHandler = null
            frameDrawQueued.set(false)
        }

        /**
         * Called whenever any SharedPreference changes — including writes from
         * MainActivity when the user adjusts speed, crop mode, or picks a new video.
         * Speed is applied cheaply to the running player; everything else needs a
         * full re-prepare since MediaPlayer doesn't support hot-swapping video source
         * or scaling mode mid-playback.
         */
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            val holder = currentHolder ?: return
            when (key) {
                PREF_PLAYBACK_SPEED -> {
                    // Speed can be applied to the running player without re-preparing.
                    val speed = sharedPreferences?.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_SPEED)
                        ?: DEFAULT_SPEED
                    if (playerPrepared) mediaPlayer?.let { applySpeed(it, speed) }
                    renderHandler?.post { gifPlayer?.setSpeed(speed) }
                }
                PREF_SCALING_MODE -> {
                    // A wallpaper's native Surface may stretch Fit video,
                    // ignoring MediaPlayer.setVideoScalingMode(). When a
                    // user switches to/from Fit in Efficient mode, switch
                    // pipelines so that Fit uses GL letterboxing. Do not
                    // re-create the decoder for changes entirely inside GL.
                    val shouldUseGl = needsGlRenderer()
                    val currentlyUsingGl = renderHandler != null
                    if (shouldUseGl != currentlyUsingGl) {
                        releasePlayer()
                        releaseRenderer()
                        configureOutput(holder)
                    } else if (currentlyUsingGl) {
                        renderHandler?.post {
                            applyScalingModePref()
                            // Redraw immediately, including paused GIF frames.
                            if (gifPlayer != null) drawFrameRunnable.run()
                            else startDrawLoopIfVisible()
                        }
                    } else if (playerPrepared) {
                        mediaPlayer?.let { applyNativeScaling(it) }
                    }
                }
                PREF_VIDEO_URI, PREF_RENDER_MODE -> {
                    // A direct Surface and an OpenGL input Surface are different
                    // video output paths; switch them only when the user changes
                    // the file or rendering mode, never on each visibility event.
                    releasePlayer()
                    releaseRenderer()
                    configureOutput(holder)
                }
            }
        }

        private fun preparePlayer(holder: SurfaceHolder) {
            releasePlayer()

            val uriString = prefs?.getString(PREF_VIDEO_URI, null)
            if (uriString.isNullOrBlank()) return
            val uri = try {
                Uri.parse(uriString)
            } catch (e: Exception) {
                Log.e(TAG, "Stored video URI was malformed", e)
                return
            }
            val speed = prefs?.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_SPEED) ?: DEFAULT_SPEED
            // Confirm we still hold read permission before touching MediaPlayer.
            // Permission can be revoked out from under us at any time (user
            // action in system settings, storage change, etc.), and without
            // this check a revoked URI fails silently deep inside setDataSource
            // with no way for the user to tell why their wallpaper went blank.
            val stillGranted = try {
                applicationContext.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check persisted URI permissions", e)
                false
            }
            if (!stillGranted) {
                Log.w(TAG, "Lost read permission for stored video URI — clearing selection")
                prefs?.edit()?.remove(PREF_VIDEO_URI)?.apply()
                return
            }

            if (isSelectedGif()) {
                prepareGif(uri, speed)
                return
            }

            try {
                val player = MediaPlayer()
                player.setDataSource(applicationContext, uri)
                // Target the renderer's input Surface (backed by a
                // SurfaceTexture), not holder.surface directly. MediaPlayer
                // decodes into that texture; VideoFrameRenderer draws it to
                // holder.surface itself with real Fill/Fit crop/letterbox
                // math, since setVideoScalingMode() has no effect on a
                // WallpaperService Engine's surface (issue #1) — kept below
                // only as an intentional no-op fallback, see comment there.
                val inputSurface = renderer?.getVideoInputSurface()
                if (inputSurface != null) {
                    player.setSurface(inputSurface)
                } else {
                    // Efficient path or EGL initialization failed. The system
                    // decoder renders directly onto the wallpaper's Surface.
                    player.setSurface(holder.surface)
                }
                player.isLooping = true
                player.setVolume(0f, 0f) // wallpapers should be silent
                player.setOnVideoSizeChangedListener { changedPlayer, width, height ->
                    if (changedPlayer === mediaPlayer) {
                        videoWidth = width
                        videoHeight = height
                        startDrawLoopIfVisible()
                    }
                }
                player.setOnPreparedListener {
                    if (it !== mediaPlayer) return@setOnPreparedListener
                    playerPrepared = true
                    videoWidth = it.videoWidth
                    videoHeight = it.videoHeight
                    consecutiveErrorRetries = 0
                    if (renderer == null) applyNativeScaling(it)
                    val latestSpeed = prefs?.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_SPEED) ?: DEFAULT_SPEED
                    applySpeed(it, latestSpeed)
                    startDrawLoopIfVisible()
                }
                player.setOnErrorListener { errorPlayer, what, extra ->
                    if (errorPlayer !== mediaPlayer) return@setOnErrorListener true
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    // The player is unusable after any error — it's stuck in
                    // MEDIA_PLAYER_STATE_ERROR and further start()/pause() calls
                    // against it (e.g. from a later onVisibilityChanged) will just
                    // keep re-triggering the same error in a loop. Release it and
                    // clear the reference immediately so nothing else can touch
                    // this broken instance; only preparePlayer() (triggered here
                    // for the source-error case, or by the next onSurfaceCreated/
                    // pref change) creates a new, working one.
                    releasePlayer()
                    // Not every MediaPlayer error means the file itself is bad —
                    // state-machine hiccups (e.g. what=-38, MEDIA_ERROR_UNSUPPORTED
                    // from an out-of-order start/pause call) are transient and a
                    // fresh preparePlayer() call will recover fine. Only clear the
                    // stored selection for errors that mean the source itself is
                    // unusable, so we don't wipe a perfectly good video over a
                    // one-off playback glitch.
                    if (what == MediaPlayer.MEDIA_ERROR_UNKNOWN || what == -38) {
                        consecutiveErrorRetries++
                        if (consecutiveErrorRetries <= MAX_CONSECUTIVE_ERROR_RETRIES) {
                            Log.w(TAG, "Transient MediaPlayer error — retrying prepare " +
                                    "($consecutiveErrorRetries/$MAX_CONSECUTIVE_ERROR_RETRIES), selection kept")
                            // Avoid instant codec reinitialization on the main
                            // thread while the lockscreen animation is active.
                            val retryGeneration = prepareGeneration
                            playbackHandler.postDelayed({
                                if (retryGeneration == prepareGeneration) {
                                    currentHolder?.let { preparePlayer(it) }
                                }
                            }, 200L * consecutiveErrorRetries)
                        } else {
                            Log.e(TAG, "Gave up after $MAX_CONSECUTIVE_ERROR_RETRIES consecutive " +
                                    "errors — leaving selection intact but not retrying further")
                        }
                    } else {
                        prefs?.edit()?.remove(PREF_VIDEO_URI)?.apply()
                    }
                    true
                }
                player.prepareAsync()
                mediaPlayer = player
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare video wallpaper", e)
                prefs?.edit()?.remove(PREF_VIDEO_URI)?.apply()
            }
        }

        /**
         * Loads and starts a GIF wallpaper. File reading and parsing happen
         * on the render thread (GIFs can be several MB), and playback is
         * driven entirely from there. Needs the renderer; without one (GL
         * setup failed) GIFs are unsupported and nothing plays.
         */
        private fun prepareGif(uri: Uri, speed: Float) {
            val handler = renderHandler
            if (handler == null || renderer == null) {
                Log.w(TAG, "Renderer not ready — cannot play GIF")
                return
            }
            val generation = prepareGeneration
            handler.post {
                if (generation != prepareGeneration) return@post
                val gif = try {
                    GifDecoder.parse(GifFiles.readBytes(applicationContext.contentResolver, uri))
                } catch (e: GifFormatException) {
                    // The file itself is unusable — same treatment as a bad video source.
                    Log.e(TAG, "Selected GIF could not be decoded — clearing selection", e)
                    prefs?.edit()?.remove(PREF_VIDEO_URI)?.apply()
                    return@post
                } catch (e: IOException) {
                    Log.e(TAG, "Could not read selected GIF — keeping selection", e)
                    return@post
                } catch (e: SecurityException) {
                    Log.e(TAG, "No permission to read selected GIF — keeping selection", e)
                    return@post
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "Out of memory loading selected GIF — keeping selection", e)
                    return@post
                }
                if (generation != prepareGeneration) return@post

                val player = GifPlayer(handler, gif) { drawGifFrameNow(it) }
                player.setSpeed(speed)
                gifPlayer = player
                if (visible) player.start()
            }
        }

        /**
         * Called by GifPlayer on the render thread for every new frame:
         * uploads it to the GL texture and, if we're visible, draws it.
         * Returns false to stop playback (renderer or surface is gone, or
         * the upload failed).
         */
        private fun drawGifFrameNow(gif: GifDecoder): Boolean {
            val r = renderer ?: return false
            val holder = currentHolder ?: return false
            if (!r.uploadGifFrame(gif.pixels, gif.width, gif.height)) return false
            if (visible) {
                r.drawGifFrame(holder.surfaceFrame.width(), holder.surfaceFrame.height())
            }
            return true
        }

        /**
         * Single guarded entry point for all player state transitions
         * (start/pause) and speed changes. Called from onPreparedListener,
         * onVisibilityChanged, and onSharedPreferenceChanged (speed changes)
         * — must be safe to call repeatedly and in any order, since any two
         * of these can land close together. Guards against duplicate start()
         * calls using our own weBelieveStarted flag rather than
         * MediaPlayer.isPlaying(), since isPlaying() was observed to lag
         * behind the actual native state transition on this device closely
         * enough for two back-to-back calls to both see it as false.
         */
        private fun applySpeed(player: MediaPlayer, speed: Float) {
            try {
                if (visible) {
                    if (!weBelieveStarted) {
                        player.start()
                        weBelieveStarted = true
                    }
                    if (speed != appliedSpeed) {
                        player.playbackParams = PlaybackParams().setSpeed(speed)
                        appliedSpeed = speed
                    }
                } else {
                    // Don't start the decoder just to set speed while hidden.
                    // Apply speed when visible playback actually starts.
                    if (weBelieveStarted) {
                        player.pause()
                        weBelieveStarted = false
                    }
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not apply playback speed on this device", e)
            }
        }

        /**
         * Ask the system video compositor to respect Fill/Fit in direct mode.
         * Some wallpaper implementations ignore this native scaling hint;
         * the Compatible OpenGL mode remains available for exact cropping.
         */
        private fun applyNativeScaling(player: MediaPlayer) {
            val requested = prefs?.getInt(PREF_SCALING_MODE, DEFAULT_SCALING_MODE)
                ?: DEFAULT_SCALING_MODE
            try {
                player.setVideoScalingMode(requested)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Native video scaling mode unavailable on this surface", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Native video scaling mode unsupported", e)
            }
        }

        /**
         * Reads PREF_SCALING_MODE and applies it to the renderer, mapping
         * from MediaPlayer's int constants (what the pref/UI already use)
         * to our ScalingMode enum. Safe to call even if renderer isn't
         * ready yet — it's a no-op until the next time it's called after
         * the renderer exists.
         */
        private fun applyScalingModePref() {
            val r = renderer ?: return
            val modeInt = prefs?.getInt(PREF_SCALING_MODE, DEFAULT_SCALING_MODE) ?: DEFAULT_SCALING_MODE
            val mode = if (modeInt == MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) {
                VideoFrameRenderer.ScalingMode.FIT
            } else {
                // Default/VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING and
                // any unrecognized value both fall back to FILL, matching
                // DEFAULT_SCALING_MODE's existing crop-based default below.
                VideoFrameRenderer.ScalingMode.FILL
            }
            r.setScalingMode(mode)
        }

        /**
         * Posts a single redraw if we're visible. Normal frame updates are
         * driven by VideoFrameRenderer's onFrameAvailableListener now, not
         * this — this exists only for the cases where the screen needs a
         * redraw but a new video frame hasn't necessarily arrived: becoming
         * visible again (the last frame may be stale/never drawn at the
         * current size) and after preparePlayer's onPreparedListener fires
         * (first frame may already be available by then). Safe to call
         * repeatedly; each call just posts one more draw.
         */
        private fun startDrawLoopIfVisible() {
            if (!visible) return
            if (frameDrawQueued.compareAndSet(false, true)) {
                val handler = renderHandler
                if (handler != null) handler.post(drawFrameRunnable)
                else frameDrawQueued.set(false)
            }
        }

        /** No-op now that drawing is event-driven rather than a loop —
         * kept so call sites (onVisibilityChanged) don't need to change,
         * and as a hook if a pending one-shot draw ever needs cancelling.
         */
        private fun stopDrawLoop() {
            renderHandler?.removeCallbacks(drawFrameRunnable)
            frameDrawQueued.set(false)
        }

        private fun releasePlayer() {
            playbackHandler.removeCallbacks(pausePlaybackRunnable)
            playbackHandler.removeCallbacks(releaseDetachedPlayerRunnable)
            // Invalidate any GIF load still in flight, and stop/discard the
            // active GIF player on the render thread that owns it. If there
            // is no render thread any more, nothing else can be using it.
            prepareGeneration++
            val gifHandler = renderHandler
            if (gifHandler != null) {
                gifHandler.post {
                    gifPlayer?.release()
                    gifPlayer = null
                }
            } else {
                gifPlayer = null
            }
            // release() is valid even when playback is active; calling
            // isPlaying() and stop() first adds synchronous native/binder
            // work on the wallpaper lifecycle thread for no benefit.
            mediaPlayer?.release()
            mediaPlayer = null
            playerPrepared = false
            awaitingVideoSurface = false
            videoWidth = 0
            videoHeight = 0
            weBelieveStarted = false
            appliedSpeed = 1.0f
        }
    }

    companion object {
        private const val TAG = "VideoWallpaperService"
        private const val MAX_CONSECUTIVE_ERROR_RETRIES = 3
        private const val VISIBILITY_PAUSE_DELAY_MS = 1100L
        private const val SURFACE_REUSE_GRACE_MS = 2500L
        const val PREF_VIDEO_URI = "selected_video_uri"
        const val PREF_PLAYBACK_SPEED = "playback_speed"
        const val PREF_SCALING_MODE = "scaling_mode"
        const val PREF_RENDER_MODE = "render_mode"
        const val PREF_SOURCE_IS_GIF = "source_is_gif"
        const val RENDER_MODE_COMPATIBLE = RenderModePolicy.COMPATIBLE
        const val RENDER_MODE_EFFICIENT = RenderModePolicy.EFFICIENT
        const val DEFAULT_RENDER_MODE = RENDER_MODE_COMPATIBLE
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_SCALING_MODE = MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    }
}