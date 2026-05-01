"""
Firebase Admin SDK wrapper for the SoundForge ClearWave backend.

Handles:
  - Firestore job queue (read queued jobs, claim atomically, update status)
  - Firebase Storage (download raw audio, upload processed output)

Storage layout (mirrors StorageManager.kt):
  users/{uid}/raw/{filename}        ← uploaded by Android client
  users/{uid}/processed/{filename}  ← written by this backend
"""

from __future__ import annotations

import logging
import os
import tempfile
from pathlib import Path
from typing import Callable, Optional

import firebase_admin
from firebase_admin import credentials, firestore, storage
from google.cloud.firestore_v1 import DocumentSnapshot, Transaction
from google.cloud.firestore_v1.base_query import FieldFilter

log = logging.getLogger(__name__)

# Firestore field constants — must match ProcessingQueueManager.Job Kotlin data class
STATUS        = "status"
RAW_FILENAME  = "rawFileName"
OUT_FILENAME  = "outputFileName"
ERROR_MSG     = "errorMessage"
INPUT_PEAK    = "inputPeakDb"
OUTPUT_PEAK   = "outputPeakDb"
NOISE_FLOOR   = "noiseFloorDb"

STATUS_QUEUED     = "queued"
STATUS_PROCESSING = "processing"
STATUS_DONE       = "done"
STATUS_ERROR      = "error"


def init_firebase(credential_path: Optional[str] = None, bucket_name: Optional[str] = None):
    """
    Initialise Firebase Admin SDK.

    In Cloud Run / GCE: set GOOGLE_CLOUD_PROJECT + let ADC handle credentials.
    Locally: set GOOGLE_APPLICATION_CREDENTIALS=path/to/serviceAccount.json.
    [bucket_name] defaults to FIREBASE_STORAGE_BUCKET env var or
    '{project_id}.appspot.com'.
    """
    if firebase_admin._apps:
        return  # already initialised

    cred_path = credential_path or os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
    cred = credentials.Certificate(cred_path) if cred_path else credentials.ApplicationDefault()

    project = os.getenv("GOOGLE_CLOUD_PROJECT")
    bucket = bucket_name or os.getenv("FIREBASE_STORAGE_BUCKET") or (
        f"{project}.appspot.com" if project else None
    )

    firebase_admin.initialize_app(cred, {"storageBucket": bucket})
    log.info("Firebase initialised (project=%s bucket=%s)", project, bucket)


def watch_queued_jobs(callback: Callable) -> object:
    """
    Attaches a real-time Firestore listener to the jobs collection-group,
    filtered to status == "queued".

    [callback] is called with (changes: list[DocumentSnapshot]) whenever
    the query result set changes.  Returns the watch handle (call .unsubscribe()
    to stop).
    """
    db = firestore.client()
    query = db.collection_group("jobs").where(
        filter=FieldFilter(STATUS, "==", STATUS_QUEUED)
    )

    def _on_snapshot(col_snapshot, changes, read_time):
        added = [c.document for c in changes if c.type.name == "ADDED"]
        if added:
            callback(added)

    return query.on_snapshot(_on_snapshot)


@firestore.transactional
def _claim_job(transaction: Transaction, job_ref) -> bool:
    """
    Atomically claim a job by updating its status from 'queued' → 'processing'.
    Returns False if another worker already claimed it.
    """
    snap: DocumentSnapshot = job_ref.get(transaction=transaction)
    if not snap.exists or snap.get(STATUS) != STATUS_QUEUED:
        return False
    transaction.update(job_ref, {STATUS: STATUS_PROCESSING})
    return True


def claim_job(job_ref) -> bool:
    """Public wrapper around the transactional claim."""
    db = firestore.client()
    return _claim_job(db.transaction(), job_ref)


def mark_done(job_ref, output_filename: str, result) -> None:
    job_ref.update({
        STATUS:       STATUS_DONE,
        OUT_FILENAME: output_filename,
        INPUT_PEAK:   result.input_peak_db,
        OUTPUT_PEAK:  result.output_peak_db,
        NOISE_FLOOR:  result.noise_floor_db,
    })


def mark_error(job_ref, message: str) -> None:
    job_ref.update({
        STATUS:    STATUS_ERROR,
        ERROR_MSG: message[:500],  # Firestore field size limit is generous but let's be safe
    })


def download_raw(uid: str, filename: str, dest: Path) -> None:
    """Download users/{uid}/raw/{filename} from Storage → dest."""
    bucket = storage.bucket()
    blob = bucket.blob(f"users/{uid}/raw/{filename}")
    blob.download_to_filename(str(dest))
    log.info("Downloaded gs://%s/users/%s/raw/%s → %s",
             bucket.name, uid, filename, dest)


def upload_processed(uid: str, filename: str, src: Path) -> None:
    """Upload src → users/{uid}/processed/{filename} in Storage."""
    bucket = storage.bucket()
    blob = bucket.blob(f"users/{uid}/processed/{filename}")
    blob.upload_from_filename(str(src), content_type="audio/mp4")
    log.info("Uploaded %s → gs://%s/users/%s/processed/%s",
             src, bucket.name, uid, filename)


def get_uid_from_job_ref(job_ref) -> str:
    """
    Extract the user UID from a job document reference.
    Path is: users/{uid}/jobs/{jobId}
    """
    # job_ref.parent = CollectionReference('jobs')
    # job_ref.parent.parent = DocumentReference('users/{uid}')
    return job_ref.parent.parent.id
