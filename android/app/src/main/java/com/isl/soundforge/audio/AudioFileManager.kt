package com.isl.soundforge.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles all audio file I/O: reading metadata, copying into app storage,
 * writing processed output to the user's Music library.
 */
class AudioFileManager(private val context: Context) {

    data class AudioMeta(
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val mimeType: String,
        val waveformData: FloatArray
    )

    /** Pick any audio file — use with ActivityResultContracts.GetContent */
    val pickAudioMime = "audio/*"

    /**
     * Extract metadata from a URI returned by the system file picker.
     * Generates a simplified waveform thumbnail (256 samples) via
     * power-envelope decimation — fast enough to do on the main thread
     * but we push it to IO just in case.
     */
    suspend fun readMeta(uri: Uri): AudioMeta = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)

        val name = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            ?: uriToFileName(uri)
        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L
        val mimeType = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_MIMETYPE
        ) ?: "audio/mpeg"

        retriever.release()

        val sizeBytes = context.contentResolver.openFileDescriptor(uri, "r")?.use {
            it.statSize
        } ?: 0L

        val waveform = generateWaveformThumbnail(uri)

        AudioMeta(uri, name, durationMs, sizeBytes, mimeType, waveform)
    }

    /**
     * Copies a content URI into the app's private files directory
     * so it's always accessible regardless of document provider state.
     */
    suspend fun copyToAppStorage(uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "recordings").also { it.mkdirs() }
        val ext = getExtension(uri) ?: "m4a"
        val dest = File(dir, "import_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
        dest
    }

    /**
     * Saves a processed file to the user's shared Music library.
     * On API 29+ uses MediaStore; below that writes to
     * Music/SoundForgeClearWave/.
     */
    suspend fun saveToMusicLibrary(file: File, displayName: String): Uri =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/aac")
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MUSIC}/SoundForgeClearWave"
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
                resolver.openOutputStream(uri)!!.use { out ->
                    file.inputStream().copyTo(out)
                }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "SoundForgeClearWave"
                ).also { it.mkdirs() }
                val dest = File(dir, displayName)
                file.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        }

    /** Creates a new File path for a fresh recording session. */
    fun newRecordingFile(): File {
        val dir = File(context.filesDir, "recordings").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "rec_$ts.aac")
    }

    // -------------------------------------------------------------------------
    // Waveform thumbnail
    // -------------------------------------------------------------------------

    /**
     * Reads the first [TARGET_FRAMES] PCM frames from the file and computes
     * a 256-bin power envelope for the waveform display.
     *
     * Accuracy doesn't matter here — we just want a visually representative
     * shape. Full waveform rendering can use a longer decode pass if needed.
     */
    private fun generateWaveformThumbnail(uri: Uri): FloatArray {
        val bins = 256
        val result = FloatArray(bins)
        try {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return result
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val durationUs = if (format.containsKey(android.media.MediaFormat.KEY_DURATION))
                format.getLong(android.media.MediaFormat.KEY_DURATION) else return result

            // Seek to evenly-spaced points and sample amplitude
            for (bin in 0 until bins) {
                val seekUs = (bin.toLong() * durationUs) / bins
                extractor.seekTo(seekUs, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val buf = java.nio.ByteBuffer.allocate(8192)
                val read = extractor.readSampleData(buf, 0)
                if (read > 0) {
                    buf.rewind()
                    var peak = 0f
                    val shorts = read / 2
                    val sb = buf.asShortBuffer()
                    repeat(shorts) {
                        val abs = Math.abs(sb.get() / 32768f)
                        if (abs > peak) peak = abs
                    }
                    result[bin] = peak
                }
            }
            extractor.release()
        } catch (_: Exception) {
            // Non-fatal — caller renders a flat line if empty
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private fun uriToFileName(uri: Uri): String {
        val path = uri.lastPathSegment ?: "audio"
        return path.substringAfterLast('/').substringAfterLast(':').ifEmpty { "audio" }
    }

    private fun getExtension(uri: Uri): String? {
        val mime = context.contentResolver.getType(uri) ?: return null
        return when (mime) {
            "audio/mpeg"  -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac"   -> "aac"
            "audio/ogg"   -> "ogg"
            "audio/flac"  -> "flac"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> mime.substringAfterLast('/')
        }
    }
}
