package com.isl.soundforge.firebase

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Handles all Firebase Storage uploads and downloads.
 *
 * Storage layout:
 *   users/{uid}/raw/{filename}        ← user-uploaded originals
 *   users/{uid}/processed/{filename}  ← backend-processed output
 */
class StorageManager {

    private val storage = FirebaseStorage.getInstance()
    private val uid get() = FirebaseAuth.getInstance().currentUser?.uid
        ?: error("Not authenticated")

    /**
     * Uploads a raw audio file and returns the download URL.
     * [onProgress] receives 0.0–1.0 upload progress.
     */
    suspend fun uploadRaw(
        file: File,
        onProgress: (Float) -> Unit = {}
    ): String {
        val ref = storage.reference.child("users/$uid/raw/${file.name}")
        // Infer MIME type from extension; fall back to generic audio so the
        // Python backend can still process it regardless of container format.
        val mimeType = when (file.extension.lowercase()) {
            "mp3"  -> "audio/mpeg"
            "m4a"  -> "audio/x-m4a"
            "ogg"  -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav"  -> "audio/wav"
            else   -> "audio/aac"
        }
        val meta = StorageMetadata.Builder()
            .setContentType(mimeType)
            .setCustomMetadata("originalName", file.name)
            .build()

        val task = ref.putFile(Uri.fromFile(file), meta)
        task.addOnProgressListener { snap ->
            val pct = snap.bytesTransferred.toFloat() / snap.totalByteCount
            onProgress(pct)
        }
        task.await()
        return ref.downloadUrl.await().toString()
    }

    /**
     * Downloads the processed output from the backend into [destFile].
     * [onProgress] receives 0.0–1.0 download progress.
     */
    suspend fun downloadProcessed(
        remoteFileName: String,
        destFile: File,
        onProgress: (Float) -> Unit = {}
    ) {
        val ref = storage.reference.child("users/$uid/processed/$remoteFileName")
        val task = ref.getFile(destFile)
        task.addOnProgressListener { snap ->
            val pct = snap.bytesTransferred.toFloat() / snap.totalByteCount
            onProgress(pct)
        }
        task.await()
    }

    /** Deletes both the raw and processed versions of a file. */
    suspend fun deleteFile(fileName: String) {
        listOf("raw", "processed").forEach { dir ->
            runCatching {
                storage.reference.child("users/$uid/$dir/$fileName").delete().await()
            }
        }
    }

    /** Lists all raw files for the current user. */
    suspend fun listRawFiles(): List<String> {
        val result = storage.reference.child("users/$uid/raw").listAll().await()
        return result.items.map { it.name }
    }
}
