package com.pockivo.nowmark.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.MainActivity
import com.pockivo.nowmark.R
import com.pockivo.nowmark.core.AudioFormatKind
import com.pockivo.nowmark.data.Preset
import com.pockivo.nowmark.player.PlayerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Owns the recording lifecycle (not the Activity). Started only by a user action:
 * the record button, the home-screen widget or the app shortcut (both open the Activity
 * first), or this service's own notification actions.
 */
class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: RecordingEngine? = null
    private var notifJob: Job? = null
    private var vibrate = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> engine?.pause()
            ACTION_RESUME -> engine?.resume()
            ACTION_MARK -> engine?.let { it.addMark(); buzz() }
            ACTION_NOTE -> intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { engine?.addNote(it) }
            ACTION_STOP -> handleStop()
            else -> if (engine == null) stopSelf()
        }
        // Do not restart automatically: the microphone may only be opened by the user.
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (engine != null) return // already recording: ignore duplicate starts from other entry points
        val preset = Preset.from(intent.getStringExtra(EXTRA_PRESET))
        val format = AudioFormatKind.from(intent.getStringExtra(EXTRA_FORMAT))
        val before = intent.getLongExtra(EXTRA_BEFORE, 30_000)
        val after = intent.getLongExtra(EXTRA_AFTER, 10_000)
        vibrate = intent.getBooleanExtra(EXTRA_VIBRATE, true)

        ensureChannel(this)
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(RecorderState.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException or missing permission.
            RecorderState.update { it.copy(phase = Phase.FAILED, failure = FailureKind.START_FAILED) }
            stopSelf()
            return
        }
        PlayerHub.pauseForRecording()
        val dir = File(Finalizer.sessionsRoot(this), UUID.randomUUID().toString())
        val e = RecordingEngine(this, dir, preset, format, before, after) { kind ->
            // Called from the capture thread on unrecoverable write errors: save what reached storage.
            RecorderState.update { it.copy(failure = kind) }
            scope.launch { handleStop() }
        }
        try {
            e.start()
        } catch (t: Throwable) {
            dir.deleteRecursively()
            RecorderState.update { it.copy(phase = Phase.FAILED, failure = FailureKind.START_FAILED) }
            stopForegroundCompat()
            stopSelf()
            return
        }
        engine = e
        notifJob = scope.launch {
            RecorderState.state
                .map { Triple(it.phase, it.marks.size, it.openInterruption != null) }
                .distinctUntilChanged()
                .collect { notify(RecorderState.state.value) }
        }
    }

    @Synchronized
    private fun handleStop() {
        val e = engine ?: return
        engine = null
        RecorderState.update { it.copy(phase = Phase.FINALIZING) }
        scope.launch(Dispatchers.IO) {
            e.stopAndJoin()
            val journal = Journal.read(e.sessionDir)
            val result = if (journal == null) FinalizeResult.Failed(FailureKind.WRITE_FAILED)
            else Finalizer.finalize(this@RecorderService, e.sessionDir, journal, recovered = false)
            RecorderState.update {
                when (result) {
                    is FinalizeResult.Saved -> it.copy(phase = Phase.SAVED, savedRecordingId = result.recordingId)
                    is FinalizeResult.Empty -> LiveState()
                    is FinalizeResult.Failed -> it.copy(
                        phase = Phase.FAILED,
                        failure = if (it.failure != FailureKind.NONE) it.failure else result.kind,
                    )
                }
            }
            notifJob?.cancel()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun buzz() {
        if (!vibrate) return
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        if (v?.hasVibrator() == true) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun notify(s: LiveState) {
        if (engine == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }

    private fun buildNotification(s: LiveState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val paused = s.phase == Phase.PAUSED
        val title = when {
            s.openInterruption != null -> getString(R.string.notif_input_interrupted)
            paused -> getString(R.string.notif_paused)
            else -> getString(R.string.notif_recording)
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(resources.getQuantityString(R.plurals.marks_count, s.marks.size, s.marks.size))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_mark_short), action(ACTION_MARK, 1))
            .addAction(
                0, getString(if (paused) R.string.action_resume else R.string.action_pause),
                action(if (paused) ACTION_RESUME else ACTION_PAUSE, 2),
            )
            .addAction(0, getString(R.string.action_save), action(ACTION_STOP, 3))
        if (!paused && s.phase == Phase.RECORDING) {
            b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - s.audioMs).setShowWhen(true)
        }
        return b.build()
    }

    private fun action(a: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RecorderService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        // If the system destroys us while recording, stop capture so the journal is final;
        // the session will be offered for recovery on next launch.
        engine?.let { e ->
            engine = null
            e.stopAndJoin()
            RecorderState.reset()
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIF_ID = 42
        const val ACTION_START = "com.pockivo.nowmark.action.START"
        const val ACTION_PAUSE = "com.pockivo.nowmark.action.PAUSE"
        const val ACTION_RESUME = "com.pockivo.nowmark.action.RESUME"
        const val ACTION_MARK = "com.pockivo.nowmark.action.MARK"
        const val ACTION_NOTE = "com.pockivo.nowmark.action.NOTE"
        const val ACTION_STOP = "com.pockivo.nowmark.action.STOP"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_BEFORE = "before"
        const val EXTRA_AFTER = "after"
        const val EXTRA_VIBRATE = "vibrate"
        const val EXTRA_TEXT = "text"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        /** Must be called from a visible Activity after RECORD_AUDIO was granted. */
        suspend fun start(context: Context) {
            val s = AppGraph.settings.current()
            val i = Intent(context, RecorderService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_PRESET, s.preset.name)
                .putExtra(EXTRA_FORMAT, s.format.name)
                .putExtra(EXTRA_BEFORE, s.markBeforeMs)
                .putExtra(EXTRA_AFTER, s.markAfterMs)
                .putExtra(EXTRA_VIBRATE, s.vibrate)
            ContextCompat.startForegroundService(context, i)
        }

        fun send(context: Context, action: String, text: String? = null) {
            val i = Intent(context, RecorderService::class.java).setAction(action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            context.startService(i)
        }
    }
}
