package com.isl.soundforge.audio

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.isl.soundforge.MainApplication
import com.isl.soundforge.MainActivity
import com.isl.soundforge.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that manages:
 *  - Audio recording (MediaRecorder)
 *  - Long-running on-device processing jobs (AudioProcessor)
 *
 * Runs as a foreground service so Android won't kill it mid-recording
 * or mid-process when the screen turns off.
 */
class AudioProcessingService : Service() {

    inner class LocalBinder : Binder() {
        fun getService() = this@AudioProcessingService
    }

    sealed class ServiceState {
        object Idle : ServiceState()
        data class Recording(val durationMs: Long, val amplitudeDb: Float) : ServiceState()
        data class Processing(val progress: Float, val label: String) : ServiceState()
        data class Done(val outputFile: File) : ServiceState()
        data class Error(val message: String) : ServiceState()
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val state: StateFlow<ServiceState> = _state

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingJob: Job? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Ready"))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    fun startRecording(outputFile: File) {
        if (_state.value is ServiceState.Recording) return

        recordingFile = outputFile
        recorder = buildRecorder(outputFile).also { it.start() }

        recordingJob = scope.launch {
            val startMs = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startMs
                val amp = recorder?.maxAmplitude?.toFloat() ?: 0f
                val db = if (amp > 0) 20f * Math.log10(amp / 32767.0).toFloat() else -60f
                _state.value = ServiceState.Recording(elapsed, db)
                updateNotification("Recording ${formatDuration(elapsed)}")
                delay(100)
            }
        }
    }

    fun stopRecording(): File? {
        recordingJob?.cancel()
        recorder?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        recorder = null
        _state.value = ServiceState.Idle
        return recordingFile
    }

    // -------------------------------------------------------------------------
    // On-device processing
    // -------------------------------------------------------------------------

    fun processFile(
        processor: AudioProcessor,
        inputUri: android.net.Uri,
        options: AudioProcessor.ProcessingOptions
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                _state.value = ServiceState.Processing(0f, "Starting…")
                val result = processor.processFile(inputUri, options) { progress ->
                    val label = when {
                        progress < 0.5f -> "Decoding audio…"
                        progress < 0.8f -> "Applying DSP…"
                        else            -> "Encoding output…"
                    }
                    _state.value = ServiceState.Processing(progress, label)
                    updateNotification(label)
                }
                _state.value = ServiceState.Done(result.outputFile)
                updateNotification("Done!")
            } catch (e: Exception) {
                _state.value = ServiceState.Error(e.message ?: "Unknown error")
                updateNotification("Error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MainApplication.CHANNEL_PROCESSING)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notification_processing_title))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildRecorder(file: File): MediaRecorder {
        // Split construction from configuration — otherwise .apply{} only
        // attaches to the else branch and the recorder is never set up on API 31+.
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this)
        else
            @Suppress("DEPRECATION") MediaRecorder()
        return recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(256_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
        }
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, AudioProcessingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
