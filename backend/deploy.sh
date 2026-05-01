#!/usr/bin/env bash
# Deploy the SoundForge ClearWave audio processing worker to Cloud Run.
#
# Prerequisites:
#   gcloud auth login
#   gcloud config set project YOUR_PROJECT_ID
#   gcloud services enable run.googleapis.com cloudbuild.googleapis.com
#
# Usage:
#   ./deploy.sh [PROJECT_ID] [BUCKET_NAME]
#
# If PROJECT_ID is omitted, reads from gcloud config.
# If BUCKET_NAME is omitted, defaults to PROJECT_ID.appspot.com.

set -euo pipefail

PROJECT="${1:-$(gcloud config get-value project)}"
BUCKET="${2:-${PROJECT}.appspot.com}"
SERVICE="soundforge-worker"
REGION="us-central1"

echo "Deploying $SERVICE to Cloud Run"
echo "  Project : $PROJECT"
echo "  Bucket  : $BUCKET"
echo "  Region  : $REGION"
echo ""

gcloud run deploy "$SERVICE" \
  --source . \
  --project "$PROJECT" \
  --region "$REGION" \
  --no-allow-unauthenticated \
  --set-env-vars "GOOGLE_CLOUD_PROJECT=${PROJECT},FIREBASE_STORAGE_BUCKET=${BUCKET}" \
  --memory 2Gi \
  --cpu 2 \
  --concurrency 2 \
  --min-instances 0 \
  --max-instances 10 \
  --timeout 3600

echo ""
echo "Deployed. To stream logs:"
echo "  gcloud logging tail 'resource.type=cloud_run_revision AND resource.labels.service_name=$SERVICE'"
