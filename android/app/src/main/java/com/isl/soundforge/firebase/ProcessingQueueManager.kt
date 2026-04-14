package com.isl.soundforge.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Manages the processing job queue stored in Firestore.
 *
 * The Python backend (Cloud Functions or Cloud Run) watches
 * the "jobs" collection for documents with status == "queued",
 * processes the audio, then writes back:
 *   status = "done" | "error"
 *   outputFileName
 *   metrics { inputPeakDb, outputPeakDb, noiseFloorDb }
 *
 * Firestore document path: users/{uid}/jobs/{jobId}
 */
class ProcessingQueueManager {

    private val db = FirebaseFirestore.getInstance()
    private val uid get() = FirebaseAuth.getInstance().currentUser?.uid
        ?: error("Not authenticated")

    data class Job(
        val id: String = "",
        val status: String = "queued",   // queued | processing | done | error
        val rawFileName: String = "",
        val outputFileName: String = "",
        val noiseReduction: Boolean = true,
        val roomCorrection: Boolean = true,
        val normalization: Boolean = true,
        val targetLUFS: Float = -14f,
        val noiseThreshold: Float = 0.3f,
        val errorMessage: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val inputPeakDb: Float = 0f,
        val outputPeakDb: Float = 0f,
        val noiseFloorDb: Float = 0f
    )

    /** Enqueues a new processing job. Returns the Firestore document ID. */
    suspend fun enqueue(job: Job): String {
        val ref = db.collection("users").document(uid)
            .collection("jobs").document()
        val withId = job.copy(id = ref.id)
        ref.set(withId).await()
        return ref.id
    }

    /**
     * Returns a [Flow] that emits the latest state of a job whenever
     * Firestore pushes an update. Cancels the listener when the flow
     * collector is cancelled.
     */
    fun watchJob(jobId: String): Flow<Job?> = callbackFlow {
        val ref = db.collection("users").document(uid)
            .collection("jobs").document(jobId)

        val listener: ListenerRegistration = ref.addSnapshotListener { snap, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            trySend(snap?.toObject(Job::class.java))
        }

        awaitClose { listener.remove() }
    }

    /** Fetches all completed jobs for the library screen. */
    suspend fun listCompletedJobs(): List<Job> {
        val snap = db.collection("users").document(uid)
            .collection("jobs")
            .whereEqualTo("status", "done")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get()
            .await()
        return snap.documents.mapNotNull { it.toObject(Job::class.java) }
    }

    /** Deletes a job document. Does not touch Storage — caller handles that. */
    suspend fun deleteJob(jobId: String) {
        db.collection("users").document(uid)
            .collection("jobs").document(jobId)
            .delete().await()
    }
}
