#!/usr/bin/env bash
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-dev-vantage-hrd}"
REGION="${REGION:-us-central1}"
REPOSITORY="${REPOSITORY:-chemvantage}"
SERVICE="${SERVICE:-chemvantage-dev}"
IMAGE_TAG="${1:-$(git rev-parse --short HEAD)}"
TASKS_OIDC_SERVICE_ACCOUNT="${TASKS_OIDC_SERVICE_ACCOUNT:-}"
SKIP_STATIC_SYNC="${SKIP_STATIC_SYNC:-false}"

echo "Deploying dev service ${SERVICE} to project ${PROJECT_ID} (region: ${REGION}, tag: ${IMAGE_TAG})"

if [[ "${SKIP_STATIC_SYNC}" != "true" ]]; then
  PROJECT_ID="${PROJECT_ID}" STATIC_BUCKET="${STATIC_BUCKET:-chemvantage-static-dev}" ./scripts/sync-static-dev.sh
else
  echo "Skipping static sync because SKIP_STATIC_SYNC=true"
fi

gcloud builds submit \
  --config cloudbuild.yaml \
  --substitutions _PROJECT_ID="${PROJECT_ID}",_REGION="${REGION}",_REPOSITORY="${REPOSITORY}",_SERVICE="${SERVICE}",_IMAGE_TAG="${IMAGE_TAG}",_TASKS_OIDC_SERVICE_ACCOUNT="${TASKS_OIDC_SERVICE_ACCOUNT}"

echo "Deployment submitted."
