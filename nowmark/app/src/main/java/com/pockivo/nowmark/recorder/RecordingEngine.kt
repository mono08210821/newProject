package com.pockivo.nowmark.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.StatFs
import android.os.SystemClock
import com.pockivo.nowmark.core.AudioFormatKind
import com.pockivo.nowmark.core.MarkMath
import com.pockivo.nowmark.data.HealthType
import com.pockivo.nowmark.data.Preset
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * Captures PCM with AudioRecord and writes it to short chunk files inside the session
 * directory. Each ~1 s the chunk is fsync'ed and the journal is atomically rewritten, so
 * "confirmed" positions shown to the user are only ever positions that reached storage.
 * Audio stays on disk (streamed), never accumulated in memory.
 */
class RecordingEngine(
    private val context: Context,
    val sessionDir: File,
    private val preset: Preset,
    private val format: AudioFormatKind,
    private val markBeforeMs: Long,
    private val markAfterMs: Long,
    private val onFatal: (FailureKind) -> Unit,
) {
    val id: String = sessionDir.name
    private val sampleRate = preset.sampleRate
    private val channels = preset.channels
    private val bytesPerFrame = channels * 2
    private val startedAt = System.currentTimeMillis()

    @Volatile private var stopRequested = false
    @Volatile private var pauseRequested = false
    @Volatile private var paused = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    private val lock = Any()
    // Guarded by lock.
    private var framesWritten = 0L
    private var confirmedFrames = 0L
    private val chunks = ArrayList<JournalChunk>()
    private val marks = ArrayList<JournalMark>()
    private val notes = ArrayList<JournalNote>()
    private val events = ArrayList<JournalEvent>()
    private var deviceLabel: String? = null

    private var chunkOut: FileOutputStream? = null
    private var chunkName: String = ""
    private var chunkFrames = 0L
    private var peaksOut: FileOutputStream? = null
    private val levels = ArrayDeque<Float>()

    private var callbackThread: HandlerThread? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)

    // Low-input tracking.
    private var quietSinceFrames = -1L
    private var lowOpen = false
    private var silencedOpen = false
    private var silencedWallStart = 0L

    @SuppressLint("MissingPermission") // The service only starts after RECORD_AUDIO is granted.
    fun start() {
        sessionDir.mkdirs()
        val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("Unsupported audio format")
        val bufferBytes = max(minBuf * 2, sampleRate * bytesPerFrame / 2)
        val source = if (preset.preferUnprocessed && unprocessedSupported()) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val r = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("AudioRecord not initialized")
        }
        record = r
        peaksOut = FileOutputStream(File(sessionDir, Journal.PEAKS_NAME), true)
        openNewChunk()
        writeJournal(Journal.STATE_RECORDING)
        registerObservers(r)
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            unregisterObservers()
            r.release()
            throw IllegalStateException("Microphone could not start")
        }
        deviceLabel = r.routedDevice?.type?.toString()
        RecorderState.update {
            it.copy(
                phase = Phase.RECORDING, sessionId = id, preset = preset, format = format,
                startedAtWall = startedAt, inputDeviceType = r.routedDevice?.type,
                silenceObservable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                bytesPerSecondEstimate = bytesPerSecondEstimate(),
                failure = FailureKind.NONE,
            )
        }
        thread = Thread({ loop(r, bufferBytes) }, "nowmark-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun unprocessedSupported(): Boolean =
        audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    /** Intermediate PCM plus the final file; used only for the "estimated time left" label. */
    private fun bytesPerSecondEstimate(): Long {
        val pcm = (sampleRate * bytesPerFrame).toLong()
        val finalFile = if (format == AudioFormatKind.WAV) pcm else (preset.aacBitrate / 8).toLong()
        return pcm + finalFile
    }

    fun pause() { pauseRequested = true }
    fun resume() { pauseRequested = false }

    /** Stops immediately; does not wait for any mark's "after" window. */
    fun stopAndJoin() {
        stopRequested = true
        pauseRequested = false
        thread?.join(5_000)
        thread = null
    }

    fun audioMs(): Long = synchronized(lock) { framesWritten * 1000L / sampleRate }

    fun addMark(): JournalMark {
        val t = audioMs()
        val reserved = MarkMath.reserve(t, markBeforeMs, markAfterMs)
        val m = JournalMark(UUID.randomUUID().toString(), t, reserved.startMs, reserved.endMs, System.currentTimeMillis())
        synchronized(lock) { marks.add(m) }
        RecorderState.update { it.copy(marks = synchronized(lock) { marks.toList() }) }
        return m
    }

    fun addNote(text: String) {
        val n = JournalNote(UUID.randomUUID().toString(), audioMs(), text, System.currentTimeMillis())
        val count = synchronized(lock) { notes.add(n); notes.size }
        RecorderState.update { it.copy(noteCount = count) }
    }

    private fun loop(r: AudioRecord, bufferBytes: Int) {
        // ~100 ms per read keeps level updates smooth and syncs cheap.
        val readBytes = (sampleRate / 10) * bytesPerFrame
        val buf = ByteArray(max(readBytes, 2))
        var lastSync = SystemClock.elapsedRealtime()
        var lastReadErrorAt = 0L
        try {
            while (!stopRequested) {
                if (pauseRequested && !paused) {
                    r.stop()
                    syncNow()
                    paused = true
                    writeJournal(Journal.STATE_PAUSED)
                    RecorderState.update { it.copy(phase = Phase.PAUSED) }
                }
                if (paused) {
                    if (!pauseRequested && !stopRequested) {
                        r.startRecording()
                        paused = false
                        writeJournal(Journal.STATE_RECORDING)
                        RecorderState.update { it.copy(phase = Phase.RECORDING) }
                    } else {
                        Thread.sleep(50)
                        continue
                    }
                }
                val n = r.read(buf, 0, buf.size)
                if (n < 0) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReadErrorAt > 5_000) {
                        lastReadErrorAt = now
                        addEvent(HealthType.READ_ERROR, audioMs(), audioMs(), "code=$n")
                    }
                    Thread.sleep(20)
                    continue
                }
                if (n == 0) continue
                val whole = n - (n % bytesPerFrame)
                try {
                    chunkOut!!.write(buf, 0, whole)
                } catch (e: IOException) {
                    addEvent(HealthType.WRITE_FAILED, audioMs(), null, e.javaClass.simpleName)
                    onFatal(if (isStorageFull()) FailureKind.STORAGE_FULL else FailureKind.WRITE_FAILED)
                    break
                }
                val frames = (whole / bytesPerFrame).toLong()
                synchronized(lock) {
                    framesWritten += frames
                    chunkFrames += frames
                }
                onLevel(peak(buf, whole), frames)

                val now = SystemClock.elapsedRealtime()
                if (now - lastSync >= 1_000) {
                    val t0 = SystemClock.elapsedRealtime()
                    try {
                        syncNow()
                    } catch (e: IOException) {
                        addEvent(HealthType.WRITE_FAILED, audioMs(), null, e.javaClass.simpleName)
                        onFatal(if (isStorageFull()) FailureKind.STORAGE_FULL else FailureKind.WRITE_FAILED)
                        break
                    }
                    val took = SystemClock.elapsedRealtime() - t0
                    if (took > 2_000) addEvent(HealthType.WRITE_DELAY, audioMs(), audioMs(), "${took}ms")
                    lastSync = now
                    if (chunkFrames >= CHUNK_SECONDS * sampleRate) rotateChunk()
                    RecorderState.update {
                        it.copy(freeBytes = freeBytes())
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try { r.stop() } catch (_: IllegalStateException) { }
            unregisterObservers()
            r.release()
            record = null
            try { syncNow() } catch (_: IOException) { }
            closeOpenEvents()
            try { chunkOut?.close() } catch (_: IOException) { }
            try { peaksOut?.close() } catch (_: IOException) { }
            writeJournal(Journal.STATE_FINALIZING)
        }
    }

    private fun peak(buf: ByteArray, len: Int): Float {
        var p = 0
        var i = 0
        while (i + 1 < len) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val a = abs(s.toShort().toInt())
            if (a > p) p = a
            i += 2
        }
        return p / 32768f
    }

    private fun onLevel(peak: Float, frames: Long) {
        try {
            peaksOut?.write((peak.coerceIn(0f, 1f) * 255).toInt())
        } catch (_: IOException) { }
        synchronized(levels) {
            levels.addLast(peak)
            while (levels.size > LEVEL_HISTORY) levels.removeFirst()
        }
        // Quiet input is reported as "low", never as a failure.
        val audioNow = audioMs()
        if (peak < LOW_PEAK) {
            if (quietSinceFrames < 0) quietSinceFrames = synchronized(lock) { framesWritten } - frames
            val quietMs = (synchronized(lock) { framesWritten } - quietSinceFrames) * 1000 / sampleRate
            if (!lowOpen && quietMs >= LOW_AFTER_MS) {
                lowOpen = true
                addEvent(HealthType.LOW_INPUT, quietSinceFrames * 1000 / sampleRate, null, "")
            }
        } else {
            if (lowOpen) closeEvent(HealthType.LOW_INPUT, audioNow)
            lowOpen = false
            quietSinceFrames = -1
        }
        val status = when {
            silencedOpen -> InputStatus.SILENCED_BY_SYSTEM
            lowOpen -> InputStatus.LOW
            else -> InputStatus.DETECTED
        }
        val snapshot = synchronized(levels) { levels.toList() }
        RecorderState.update {
            it.copy(audioMs = audioNow, levels = snapshot, inputStatus = status)
        }
    }

    private fun syncNow() {
        val out = chunkOut ?: return
        out.flush()
        out.fd.sync()
        try { peaksOut?.let { it.flush(); it.fd.sync() } } catch (_: IOException) { }
        synchronized(lock) {
            confirmedFrames = framesWritten
            replaceCurrentChunk()
        }
        writeJournal(if (paused) Journal.STATE_PAUSED else Journal.STATE_RECORDING)
        RecorderState.update { it.copy(confirmedMs = confirmedFrames * 1000L / sampleRate) }
    }

    private fun replaceCurrentChunk() {
        val idx = chunks.indexOfFirst { it.name == chunkName }
        val c = JournalChunk(chunkName, chunkFrames)
        if (idx >= 0) chunks[idx] = c else chunks.add(c)
    }

    private fun openNewChunk() {
        val index = synchronized(lock) { chunks.size } + 1
        chunkName = String.format(java.util.Locale.ROOT, "chunk_%05d.pcm", index)
        chunkOut = FileOutputStream(File(sessionDir, chunkName))
        synchronized(lock) {
            chunkFrames = 0
            chunks.add(JournalChunk(chunkName, 0))
        }
    }

    private fun rotateChunk() {
        chunkOut?.close()
        openNewChunk()
        writeJournal(if (paused) Journal.STATE_PAUSED else Journal.STATE_RECORDING)
    }

    fun writeJournal(state: String) {
        val j = synchronized(lock) {
            Journal(
                id = id, preset = preset.name, format = format.name, sampleRate = sampleRate,
                channels = channels, startedAt = startedAt, chunks = chunks.toList(),
                confirmedFrames = confirmedFrames, marks = marks.toList(), notes = notes.toList(),
                events = events.toList(), state = state, inputDevice = deviceLabel,
                updatedAt = System.currentTimeMillis(),
            )
        }
        try {
            j.writeAtomically(sessionDir)
        } catch (_: IOException) {
            // The chunk files are still there; recovery can rebuild from them.
        }
    }

    private fun addEvent(type: HealthType, startMs: Long, endMs: Long?, detail: String) {
        val e = JournalEvent(type.name, startMs, endMs, System.currentTimeMillis(), 0, detail)
        val list = synchronized(lock) { events.add(e); events.toList() }
        publishEvents(list)
    }

    private fun closeEvent(type: HealthType, endMs: Long) {
        val list = synchronized(lock) {
            val idx = events.indexOfLast { it.type == type.name && it.endMs == null }
            if (idx >= 0) {
                val e = events[idx]
                events[idx] = e.copy(endMs = endMs, wallDurationMs = System.currentTimeMillis() - e.wallTime)
            }
            events.toList()
        }
        publishEvents(list)
    }

    private fun closeOpenEvents() {
        val end = audioMs()
        HealthType.entries.forEach { t ->
            if (synchronized(lock) { events.any { it.type == t.name && it.endMs == null } }) closeEvent(t, end)
        }
    }

    private fun publishEvents(list: List<JournalEvent>) {
        RecorderState.update { s ->
            s.copy(events = list.map { LiveEvent(it.type, it.startMs, it.endMs, it.wallTime, it.wallDurationMs, it.detail) })
        }
    }

    private fun registerObservers(r: AudioRecord) {
        val ht = HandlerThread("nowmark-audio-callbacks").also { it.start() }
        callbackThread = ht
        val handler = Handler(ht.looper)
        r.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { router ->
            val type = router.routedDevice?.type
            val old = deviceLabel
            deviceLabel = type?.toString()
            if (old != null && old != deviceLabel) {
                addEvent(HealthType.DEVICE_CHANGED, audioMs(), audioMs(), "$old>${deviceLabel ?: "?"}")
            }
            RecorderState.update { it.copy(inputDeviceType = type) }
        }, handler)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>?) {
                    if (configs == null) return
                    val session = record?.audioSessionId ?: return
                    val mine = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
                    val silenced = mine.isClientSilenced
                    if (silenced && !silencedOpen) {
                        silencedOpen = true
                        silencedWallStart = System.currentTimeMillis()
                        addEvent(HealthType.INPUT_SILENCED, audioMs(), null, "")
                        RecorderState.update { it.copy(inputStatus = InputStatus.SILENCED_BY_SYSTEM) }
                    } else if (!silenced && silencedOpen) {
                        silencedOpen = false
                        closeEvent(HealthType.INPUT_SILENCED, audioMs())
                    }
                }
            }
            audioManager.registerAudioRecordingCallback(cb, handler)
            recordingCallback = cb
        }
    }

    private fun unregisterObservers() {
        recordingCallback?.let { audioManager.unregisterAudioRecordingCallback(it) }
        recordingCallback = null
        callbackThread?.quitSafely()
        callbackThread = null
    }

    private fun freeBytes(): Long = try {
        StatFs(sessionDir.absolutePath).availableBytes
    } catch (_: IllegalArgumentException) {
        -1
    }

    private fun isStorageFull(): Boolean {
        val free = freeBytes()
        return free in 0..(4L * 1024 * 1024)
    }

    companion object {
        const val CHUNK_SECONDS = 300L
        const val LEVEL_HISTORY = 120
        /** About -50 dBFS. */
        const val LOW_PEAK = 0.003f
        const val LOW_AFTER_MS = 10_000L

        /** Type names the UI understands, used for event details. */
        fun deviceTypeIsBluetooth(type: Int?): Boolean =
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}
