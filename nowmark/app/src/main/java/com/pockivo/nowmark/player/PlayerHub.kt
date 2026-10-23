package com.pockivo.nowmark.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.pockivo.nowmark.core.TimeRange
import com.pockivo.nowmark.recorder.RecorderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One segment in a play queue, in ORIGINAL audio coordinates. */
data class QueueItem(
    val recordingId: String,
    val title: String,
    val createdAt: Long,
    val uri: String,
    val range: TimeRange?,
    val markId: String? = null,
)

data class PlayerUi(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val index: Int = 0,
    val queue: List<QueueItem> = emptyList(),
    val speed: Float = 1f,
    val repeatOne: Boolean = false,
    val skipSilence: Boolean = false,
) {
    val current: QueueItem? get() = queue.getOrNull(index)
    /** Ads must stay away while anything is playing or loading. */
    val isActive: Boolean get() = isPlaying || isBuffering
}

/**
 * App-wide ExoPlayer. The MediaSessionService wraps the same instance so playback keeps
 * running with the screen off and is controllable from the notification / headset.
 */
@OptIn(UnstableApi::class)
object PlayerHub {
    private var exo: ExoPlayer? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    fun player(context: Context): ExoPlayer {
        exo?.let { return it }
        val p = ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                _ui.value = _ui.value.copy(
                    isPlaying = player.isPlaying,
                    isBuffering = player.playbackState == Player.STATE_BUFFERING,
                    index = player.currentMediaItemIndex,
                    speed = player.playbackParameters.speed,
                    repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE,
                )
            }
        })
        exo = p
        return p
    }

    /** Binds the session service so it can promote itself to foreground while playing. */
    private fun connect(context: Context) {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context.applicationContext, token).buildAsync()
    }

    private fun item(q: QueueItem): MediaItem {
        val b = MediaItem.Builder()
            .setUri(Uri.parse(q.uri))
            .setMediaId("${q.recordingId}|${q.range?.startMs ?: 0}|${q.markId ?: ""}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(q.title).setArtist("Nowmark").build())
        q.range?.let {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(it.startMs)
                    .setEndPositionMs(it.endMs)
                    .build()
            )
        }
        return b.build()
    }

    /** Returns false (and plays nothing) while a recording is in progress. */
    fun play(context: Context, queue: List<QueueItem>, startIndex: Int = 0, startPositionMs: Long = 0, autoPlay: Boolean = true): Boolean {
        if (RecorderState.state.value.isActive || queue.isEmpty()) return false
        connect(context)
        val p = player(context)
        p.setMediaItems(queue.map(::item), startIndex.coerceIn(0, queue.lastIndex), startPositionMs)
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.prepare()
        p.playWhenReady = autoPlay
        _ui.value = _ui.value.copy(queue = queue, index = startIndex, repeatOne = false)
        return true
    }

    fun toggle(context: Context) {
        val p = player(context)
        if (p.isPlaying) p.pause() else if (!RecorderState.state.value.isActive) {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
            p.play()
        }
    }

    fun pause() { exo?.pause() }

    fun pauseForRecording() { exo?.pause() }

    /** Seek to an ORIGINAL audio position inside the current item. */
    fun seekOriginal(positionMs: Long) {
        val p = exo ?: return
        val start = _ui.value.current?.range?.startMs ?: 0
        p.seekTo((positionMs - start).coerceAtLeast(0))
    }

    fun skipBy(deltaMs: Long) {
        val p = exo ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0))
    }

    /** Current position in ORIGINAL audio coordinates. */
    fun originalPositionMs(): Long {
        val p = exo ?: return 0
        val start = _ui.value.current?.range?.startMs ?: 0
        return start + p.currentPosition
    }

    fun next() { exo?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    fun previous() { exo?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) } }

    fun setSpeed(speed: Float) { exo?.playbackParameters = PlaybackParameters(speed) }

    fun setRepeatOne(on: Boolean) {
        exo?.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Playback option only; the file is never changed. */
    fun setSkipSilence(context: Context, on: Boolean) {
        player(context).skipSilenceEnabled = on
        _ui.value = _ui.value.copy(skipSilence = on)
    }

    fun stop() {
        exo?.stop()
        exo?.clearMediaItems()
        _ui.value = PlayerUi(speed = _ui.value.speed, skipSilence = _ui.value.skipSilence)
    }
}
