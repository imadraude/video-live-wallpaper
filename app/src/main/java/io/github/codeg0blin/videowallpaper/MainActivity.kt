package io.github.codeg0blin.videowallpaper

import android.app.WallpaperManager
import android.graphics.Point
import android.os.Build
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.preference.PreferenceManager
import io.github.codeg0blin.videowallpaper.R
import io.github.codeg0blin.videowallpaper.VideoWallpaperService
import io.github.codeg0blin.videowallpaper.databinding.ActivityMainBinding
import io.github.codeg0blin.videowallpaper.databinding.DialogAdvancedSettingsBinding
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Lets the user pick a video, preview it, and set it as a live wallpaper
 * via the system's live-wallpaper picker. Exposes playback speed and
 * crop-mode controls, both applied live to the preview and persisted for
 * [VideoWallpaperService] to read. Live wallpapers apply to both home and
 * lock screen simultaneously — Android does not support independent
 * live wallpapers per surface.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var selectedVideoUri: Uri? = null

    // GIFs have their own decoder; videos use the same Media3 engine family as wallpapers.
    private lateinit var gifPreview: GifPreview
    private var showingGif = false

    // Keep a single prepared decoder for the whole preview session. Media3's
    // repeat timeline loops without VideoView's platform-level restart path.
    private var previewPlayer: ExoPlayer? = null
    private var currentSpeed: Float = VideoWallpaperService.Companion.DEFAULT_SPEED
    private var currentScalingMode: Int = VideoWallpaperService.Companion.DEFAULT_SCALING_MODE

    // Debounce handler — delays writing speed to prefs until 500ms after the
    // user lifts their finger, so rapid slider adjustments don't each trigger
    // a wallpaper service re-prepare.
    private val speedDebounceHandler = Handler(Looper.getMainLooper())
    private val speedDebounceRunnable = Runnable {
        prefs.edit().putFloat(VideoWallpaperService.Companion.PREF_PLAYBACK_SPEED, currentSpeed).apply()
    }

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onVideoPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configurePreviewAspectRatio()
        binding.versionText.text = getString(R.string.version_format, appVersionName())

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        gifPreview = GifPreview(binding.previewGif)
        binding.previewFrame.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                applyCropToVideoPreview()
            }
        }

        currentSpeed = prefs.getFloat(VideoWallpaperService.Companion.PREF_PLAYBACK_SPEED, VideoWallpaperService.Companion.DEFAULT_SPEED)
        currentScalingMode = prefs.getInt(VideoWallpaperService.Companion.PREF_SCALING_MODE, VideoWallpaperService.Companion.DEFAULT_SCALING_MODE)

        setupCropControl()
        binding.advancedToggle.setOnClickListener { showAdvancedSettings() }
        restoreSelection()

        binding.pickButton.setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*", "image/gif"))
        }

        binding.setWallpaperButton.setOnClickListener {
            launchLiveWallpaperPicker()
        }
    }

    /**
     * Keep the entire preview visible in the available area, with the exact
     * full-display aspect ratio. ConstraintLayout scales both match-constraint
     * dimensions down as needed; the main screen never requires scrolling.
     */
    @Suppress("DEPRECATION")
    private fun configurePreviewAspectRatio() {
        val (screenWidth, screenHeight) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val size = Point().also { windowManager.defaultDisplay.getRealSize(it) }
                size.x to size.y
            }
        if (screenWidth <= 0 || screenHeight <= 0) return
        val params = binding.previewCard.layoutParams as ConstraintLayout.LayoutParams
        params.dimensionRatio = "W,$screenWidth:$screenHeight"
        binding.previewCard.layoutParams = params
    }

    private fun showAdvancedSettings() {
        val sheet = BottomSheetDialog(this)
        val controls = DialogAdvancedSettingsBinding.inflate(layoutInflater)
        sheet.setContentView(controls.root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        setupSpeedControl(controls)
        setupRenderModeControl(controls)
        setupPlaybackEngineControl(controls)
        sheet.setOnDismissListener {
            // Persist a pending speed change, but don't trigger a wallpaper
            // re-prepare when somebody merely opens and closes settings.
            speedDebounceHandler.removeCallbacks(speedDebounceRunnable)
            val storedSpeed = prefs.getFloat(
                VideoWallpaperService.PREF_PLAYBACK_SPEED,
                VideoWallpaperService.DEFAULT_SPEED
            )
            if (storedSpeed != currentSpeed) {
                prefs.edit()
                    .putFloat(VideoWallpaperService.PREF_PLAYBACK_SPEED, currentSpeed)
                    .apply()
            }
        }
        sheet.show()
    }

    private fun showPreviewLoading() {
        binding.previewProgress.visibility = View.VISIBLE
        binding.noVideoText.visibility = View.GONE
        binding.setWallpaperButton.isEnabled = false
    }

    private fun showPreviewReady() {
        binding.previewProgress.visibility = View.GONE
        binding.noVideoText.visibility = View.GONE
        binding.setWallpaperButton.isEnabled = true
    }

    private fun showPreviewError() {
        binding.previewProgress.visibility = View.GONE
        binding.noVideoText.setText(R.string.error_preview_unavailable)
        binding.noVideoText.visibility = View.VISIBLE
        binding.setWallpaperButton.isEnabled = false
    }

    /** The installed app's own versionName, so the on-screen label can't drift from build.gradle.kts. */
    @Suppress("DEPRECATION")
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

    // --- Speed control -------------------------------------------------

    /**
     * SeekBar progress (0..35) maps to speed 0.25x..2.0x in 0.05x steps,
     * with progress 15 landing exactly on 1.0x (normal speed) so the default
     * thumb position reads naturally as "no change."
     */
    private fun progressToSpeed(progress: Int): Float = 0.25f + (progress * 0.05f)
    private fun speedToProgress(speed: Float): Int = (((speed - 0.25f) / 0.05f) + 0.5f).toInt()

    private fun setupSpeedControl(controls: DialogAdvancedSettingsBinding) {
        controls.speedSeekBar.progress = speedToProgress(currentSpeed).coerceIn(0, 35)
        updateSpeedLabel(currentSpeed, controls)

        controls.speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progressToSpeed(progress)
                updateSpeedLabel(speed, controls)
                if (fromUser) {
                    currentSpeed = speed
                    applySpeedToPreview(speed)
                    gifPreview.setSpeed(speed)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Cancel any pending debounced write when the user starts
                // dragging again before the delay has elapsed.
                speedDebounceHandler.removeCallbacks(speedDebounceRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Write to prefs 500ms after finger lifts — giving the user
                // time to make a second adjustment before the wallpaper
                // service does an expensive re-prepare.
                speedDebounceHandler.postDelayed(speedDebounceRunnable, 500L)
            }
        })
    }

    private fun updateSpeedLabel(speed: Float, controls: DialogAdvancedSettingsBinding) {
        controls.speedValueText.text = String.format("%.2fx", speed)
    }

    private fun applySpeedToPreview(speed: Float) {
        // Changing speed never recreates or seeks the decoder.
        previewPlayer?.setPlaybackSpeed(speed)
    }

    // --- Crop / scaling mode control ------------------------------------

    private fun setupCropControl() {
        val initialCheckedId = if (currentScalingMode == MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) {
            binding.cropFitButton.id
        } else {
            binding.cropFillButton.id
        }
        binding.cropToggleGroup.check(initialCheckedId)

        binding.cropToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentScalingMode = if (checkedId == binding.cropFitButton.id) {
                MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT
            } else {
                MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
            prefs.edit().putInt(VideoWallpaperService.Companion.PREF_SCALING_MODE, currentScalingMode).apply()
            applyCropToGifPreview()
            applyCropToVideoPreview()
            // PlayerView applies Fit/Fill immediately; the preference also
            // tells the wallpaper engine how to crop its own output.
        }
    }

    // --- Video rendering mode -------------------------------------------

    private fun setupRenderModeControl(controls: DialogAdvancedSettingsBinding) {
        val saved = prefs.getInt(
            VideoWallpaperService.PREF_RENDER_MODE,
            VideoWallpaperService.DEFAULT_RENDER_MODE
        )
        controls.renderModeToggleGroup.check(
            if (saved == VideoWallpaperService.RENDER_MODE_EFFICIENT) {
                controls.renderEfficientButton.id
            } else {
                controls.renderCompatibleButton.id
            }
        )

        controls.renderModeToggleGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val newMode = if (checkedId == controls.renderEfficientButton.id) {
                VideoWallpaperService.RENDER_MODE_EFFICIENT
            } else {
                VideoWallpaperService.RENDER_MODE_COMPATIBLE
            }
            prefs.edit().putInt(VideoWallpaperService.PREF_RENDER_MODE, newMode).apply()
        }
    }

    // --- Video playback engine --------------------------------------------

    private fun setupPlaybackEngineControl(controls: DialogAdvancedSettingsBinding) {
        val initial = PlaybackEnginePolicy.resolve(
            prefs.getInt(
                VideoWallpaperService.PREF_PLAYBACK_ENGINE,
                VideoWallpaperService.DEFAULT_PLAYBACK_ENGINE
            )
        )
        controls.playbackEngineToggleGroup.check(
            if (initial == VideoWallpaperService.PLAYBACK_ENGINE_MEDIA3) {
                controls.playbackMedia3Button.id
            } else {
                controls.playbackSystemButton.id
            }
        )
        controls.playbackEngineToggleGroup.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val engine = if (id == controls.playbackMedia3Button.id) {
                VideoWallpaperService.PLAYBACK_ENGINE_MEDIA3
            } else VideoWallpaperService.PLAYBACK_ENGINE_SYSTEM
            prefs.edit().putInt(VideoWallpaperService.PREF_PLAYBACK_ENGINE, engine).apply()
        }
    }

    // --- Video selection -------------------------------------------------

    private fun restoreSelection() {
        val saved = prefs.getString(VideoWallpaperService.Companion.PREF_VIDEO_URI, null)
        if (saved.isNullOrBlank()) return

        val uri = try {
            Uri.parse(saved)
        } catch (e: Exception) {
            // Stored value was somehow malformed — treat as no selection
            // rather than crashing on launch.
            null
        } ?: return

        // Confirm we still hold permission; if not, treat as no selection.
        val stillGranted = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (stillGranted) {
            selectedVideoUri = uri
            showPreview(uri)
        }
    }

    private fun onVideoPicked(uri: Uri) {
        // Persist permission so the wallpaper service can still read this file
        // after the picker activity closes, and across device reboots.
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.error_file_permission), Toast.LENGTH_LONG).show()
            return
        }

        if (GifFiles.looksLikeGif(contentResolver, uri)) {
            // Validate before committing, so a damaged or oversized GIF never
            // replaces a working selection.
            loadGifPreview(uri, persistSelection = true)
            return
        }

        selectedVideoUri = uri
        prefs.edit()
            .putBoolean(VideoWallpaperService.PREF_SOURCE_IS_GIF, false)
            .putString(VideoWallpaperService.Companion.PREF_VIDEO_URI, uri.toString())
            .apply()
        showPreview(uri)
    }

    // --- Video preview crop ------------------------------------------------

    /**
     * PlayerView handles the video's dimensions, rotation and pixel aspect
     * ratio. Resize the video within the preview card instead of repeatedly
     * resizing/recreating the underlying Surface when the crop changes.
     */
    private fun applyCropToVideoPreview() {
        binding.previewVideo.resizeMode =
            if (currentScalingMode == MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
    }

    // --- GIF preview ------------------------------------------------------

    private fun applyCropToGifPreview() {
        binding.previewGif.scaleType =
            if (currentScalingMode == MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) {
                ImageView.ScaleType.FIT_CENTER
            } else {
                ImageView.ScaleType.CENTER_CROP
            }
    }

    /**
     * Loads [uri] as a GIF in the background. On success it becomes the
     * selection (saved to prefs only when [persistSelection] is true, i.e.
     * the user just picked it) and the preview switches to the GIF. On
     * failure the previous selection and preview are left alone.
     */
    private fun loadGifPreview(uri: Uri, persistSelection: Boolean) {
        showPreviewLoading()
        gifPreview.load(contentResolver, uri) { ok ->
            if (!ok) {
                if (persistSelection) {
                    Toast.makeText(this, getString(R.string.error_gif_unusable), Toast.LENGTH_LONG).show()
                    // Give back the permission we took for a file we won't use,
                    // unless it is the file already selected.
                    if (uri != selectedVideoUri) {
                        try {
                            contentResolver.releasePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: SecurityException) {
                            // Nothing to release.
                        }
                    }
                } else {
                    // Restoring a saved selection that no longer loads: treat as no selection.
                    selectedVideoUri = null
                }
                if (persistSelection && selectedVideoUri != null && selectedVideoUri != uri) {
                    // A failed new GIF must not destroy the previously selected wallpaper.
                    showPreview(selectedVideoUri!!)
                } else {
                    showPreviewError()
                }
                return@load
            }

            selectedVideoUri = uri
            if (persistSelection) {
                prefs.edit()
                    .putBoolean(VideoWallpaperService.PREF_SOURCE_IS_GIF, true)
                    .putString(VideoWallpaperService.Companion.PREF_VIDEO_URI, uri.toString())
                    .apply()
            }
            showingGif = true

            releaseVideoPreview()
            binding.previewVideo.visibility = View.GONE
            binding.noVideoText.visibility = View.GONE
            binding.previewGif.visibility = View.VISIBLE
            applyCropToGifPreview()
            gifPreview.setSpeed(currentSpeed)
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                gifPreview.start()
            }
            showPreviewReady()
        }
    }

    private fun showPreview(uri: Uri) {
        if (GifFiles.looksLikeGif(contentResolver, uri)) {
            loadGifPreview(uri, persistSelection = false)
        } else {
            showVideoPreview(uri)
        }
    }

    private fun releaseVideoPreview() {
        val player = previewPlayer
        previewPlayer = null
        // Detach the surface before freeing the codec, including when a GIF is selected.
        binding.previewVideo.player = null
        player?.release()
    }

    private fun showVideoPreview(uri: Uri) {
        showingGif = false
        gifPreview.stop()
        showPreviewLoading()
        binding.previewGif.visibility = View.GONE
        binding.previewVideo.visibility = View.VISIBLE
        releaseVideoPreview()
        applyCropToVideoPreview()

        try {
            val player = ExoPlayer.Builder(this).build()
            previewPlayer = player
            binding.previewVideo.player = player

            // Loop inside ExoPlayer's timeline. A manual seek or a MediaPlayer
            // completion/restart at every boundary can produce a visible pause.
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.volume = 0f
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            player.setPlaybackSpeed(currentSpeed)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (previewPlayer !== player || selectedVideoUri != uri) return
                    if (playbackState == Player.STATE_READY) showPreviewReady()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (previewPlayer !== player || selectedVideoUri != uri) return
                    if (videoSize.width <= 0 || videoSize.height <= 0) return
                    // Store the displayed (rotation-corrected) aspect for the
                    // wallpaper engine's preview/mode decisions.
                    val rotated = videoSize.unappliedRotationDegrees % 180 != 0
                    val width = if (rotated) videoSize.height else videoSize.width
                    val height = if (rotated) videoSize.width else videoSize.height
                    prefs.edit()
                        .putString(VideoWallpaperService.PREF_SOURCE_ASPECT_URI, uri.toString())
                        .putInt(VideoWallpaperService.PREF_SOURCE_ASPECT_WIDTH, width)
                        .putInt(VideoWallpaperService.PREF_SOURCE_ASPECT_HEIGHT, height)
                        .apply()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (previewPlayer === player && selectedVideoUri == uri) {
                        showPreviewError()
                    }
                }
            })
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            // Never decode in the background while the wallpaper picker is open.
            player.playWhenReady =
                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        } catch (e: Exception) {
            releaseVideoPreview()
            showPreviewError()
        }
    }

    private fun launchLiveWallpaperPicker() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, getString(R.string.select_video_first), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        intent.putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, VideoWallpaperService::class.java)
        )

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, getString(R.string.error_live_wallpaper_unavailable), Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (showingGif) {
            gifPreview.start()
        } else {
            previewPlayer?.playWhenReady = true
        }
    }

    override fun onPause() {
        super.onPause()
        gifPreview.pause()
        previewPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        gifPreview.release()
        releaseVideoPreview()
        speedDebounceHandler.removeCallbacks(speedDebounceRunnable)
        super.onDestroy()
    }
}
