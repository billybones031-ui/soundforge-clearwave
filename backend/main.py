"""
SoundForge ClearWave — cloud audio processing worker.

Runs as a long-lived process (Cloud Run or local).  Listens to Firestore
for queued jobs, processes each one, then updates the job document.

Environment variables:
  GOOGLE_APPLICATION_CREDENTIALS  path to service account JSON (local dev)
  GOOGLE_CLOUD_PROJECT             GCP project ID
  FIREBASE_STORAGE_BUCKET          Storage bucket name (optional, defaults to
                                   {project}.appspot.com)
  WORKER_CONCURRENCY               max parallel jobs (default 2)
  LOG_LEVEL                        DEBUG/INFO/WARNING (default INFO)
"""

from __future__ import annotations

import logging
import os
import signal
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import firebase_client as fc
import audio_processor as ap

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s — %(message)s",
)
log = logging.getLogger("worker")

# ---------------------------------------------------------------------------
# Job processing
# ---------------------------------------------------------------------------

def process_job(job_doc) -> None:
    """
    Full lifecycle for one job document:
      claim → download → DSP → upload → mark done/error
    """
    job_ref  = job_doc.reference
    job_data = job_doc.to_dict() or {}
    job_id   = job_ref.id
    uid      = fc.get_uid_from_job_ref(job_ref)

    log.info("[%s] Picked up job for uid=%s", job_id, uid)

    # Atomically claim the job — guards against duplicate processing
    if not fc.claim_job(job_ref):
        log.info("[%s] Already claimed by another worker, skipping", job_id)
        return

    raw_filename = job_data.get("rawFileName", "")
    if not raw_filename:
        fc.mark_error(job_ref, "rawFileName is empty")
        return

    options = ap.ProcessingOptions(
        noise_reduction = bool(job_data.get("noiseReduction", True)),
        room_correction  = bool(job_data.get("roomCorrection", True)),
        normalization    = bool(job_data.get("normalization", True)),
        target_lufs      = float(job_data.get("targetLUFS", -14.0)),
        noise_threshold  = float(job_data.get("noiseThreshold", 0.3)),
    )

    with tempfile.TemporaryDirectory(prefix="sfcw_") as tmp_dir:
        tmp = Path(tmp_dir)
        raw_path  = tmp / raw_filename
        out_name  = f"enhanced_{raw_filename.rsplit('.', 1)[0]}.m4a"
        out_path  = tmp / out_name

        try:
            # 1. Download raw audio
            log.info("[%s] Downloading raw/%s", job_id, raw_filename)
            fc.download_raw(uid, raw_filename, raw_path)

            # 2. Process
            log.info("[%s] Processing (noise=%s room=%s norm=%s lufs=%.0f)",
                     job_id, options.noise_reduction, options.room_correction,
                     options.normalization, options.target_lufs)
            result = ap.process_file(
                raw_path, out_path, options,
                on_progress=lambda p: log.debug("[%s] Progress %.0f%%", job_id, p * 100),
            )

            # 3. Upload processed output
            log.info("[%s] Uploading processed/%s", job_id, out_name)
            fc.upload_processed(uid, out_name, out_path)

            # 4. Mark done
            fc.mark_done(job_ref, out_name, result)
            log.info("[%s] Done — in=%.1f dBFS out=%.1f dBFS (%.1fs)",
                     job_id, result.input_peak_db, result.output_peak_db,
                     result.duration_ms / 1000)

        except Exception as exc:
            log.exception("[%s] Failed: %s", job_id, exc)
            fc.mark_error(job_ref, str(exc))


# ---------------------------------------------------------------------------
# Main loop
# ---------------------------------------------------------------------------

def main() -> None:
    fc.init_firebase()

    concurrency = int(os.getenv("WORKER_CONCURRENCY", "2"))
    executor    = ThreadPoolExecutor(max_workers=concurrency,
                                     thread_name_prefix="sfcw")

    running = True

    def _shutdown(sig, frame):
        nonlocal running
        log.info("Shutdown signal received, draining…")
        running = False

    signal.signal(signal.SIGTERM, _shutdown)
    signal.signal(signal.SIGINT,  _shutdown)

    log.info("Worker started (concurrency=%d)", concurrency)

    def _on_jobs(job_docs):
        for doc in job_docs:
            executor.submit(process_job, doc)

    watch = fc.watch_queued_jobs(_on_jobs)

    try:
        while running:
            time.sleep(1)
    finally:
        watch.unsubscribe()
        executor.shutdown(wait=True, cancel_futures=False)
        log.info("Worker stopped cleanly")


if __name__ == "__main__":
    main()
