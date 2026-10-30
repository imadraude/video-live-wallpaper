package io.github.codeg0blin.videowallpaper

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Media3 playback backend for an existing WallpaperService Engine.
 *
 * Android's hardware codec still decodes the video. This class only owns the
 * Media3 player and its output Surface; it never owns EGL or the wallpaper's
 * Surface, and must only be used from the main (application) Looper.
 * Releasing the renderer must be coordinated by VideoWallpaperService.
 */
@OptIn(UnstableApi::class)
class Media3WallpaperPlayer(
    context: Context,
    uri: Uri,
    initialSurface: Surface,
    initialSpeed: Float,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onReady: () -> Unit,
    private val onError: (PlaybackException) -> Unit
) {
    private val createdAtMs = SystemClock.elapsedRealtime()
    private val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private var released = false
    private var lastLoopTimestampMs = 0L
    private var totalDroppedFrames = 0L

    var prepared: Boolean = false
        private set

    init {
        player.repeatMode = Player.REPEAT_MODE_ONE
        // Wallpapers are silent. Disable audio-track selection as well so
        // an audio-bearing MP4 doesn't waste resources on decoding sound.
        player.volume = 0f
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        player.setPlaybackSpeed(initialSpeed)
        player.setVideoSurface(initialSurface)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (released) return
                if (playbackState == Player.STATE_READY) {
                    if (!prepared) {
                        prepared = true
                        Log.i(TAG, "Media3 prepared in ${SystemClock.elapsedRealtime() - createdAtMs}ms")
                    }
                    onReady()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!released) onVideoSize(videoSize.width, videoSize.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!released) {
                    Log.e(TAG, "Media3 playback error: ${error.errorCodeName}", error)
                    onError(error)
                }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                totalDroppedFrames += droppedFrames.toLong()
                Log.i(TAG, "Media3 dropped=${droppedFrames} in ${elapsedMs}ms (total=$totalDroppedFrames)")
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                Log.i(TAG, "Media3 first frame, prepare-to-render=${renderTimeMs - createdAtMs}ms")
            }

            override fun onPositionDiscontinuity(
                eventTime: AnalyticsListener.EventTime,
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    val now = SystemClock.elapsedRealtime()
                    val sincePrevious = if (lastLoopTimestampMs == 0L) -1L
                        else now - lastLoopTimestampMs
                    lastLoopTimestampMs = now
                    Log.d(TAG, "Media3 repeat boundary, prev=${oldPosition.positionMs}ms" +
                        ", next=${newPosition.positionMs}ms, sincePrevious=${sincePrevious}ms")
                }
            }
        })
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun setSurface(surface: Surface) {
        if (!released) player.setVideoSurface(surface)
    }

    fun detachSurface() {
        if (!released) player.clearVideoSurface()
    }

    fun setPlaying(playing: Boolean) {
        if (!released) player.playWhenReady = playing
    }

    fun setSpeed(speed: Float) {
        if (!released) player.setPlaybackSpeed(speed)
    }

    fun release() {
        if (released) return
        released = true
        player.clearVideoSurface()
        player.release()
        Log.i(TAG, "Media3 released, dropped video frames=$totalDroppedFrames")
    }

    companion object {
        private const val TAG = "Media3WallpaperPlayer"
    }
}
