#!/usr/bin/env bash
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-dev-vantage-hrd}"
REGION="${REGION:-us-central1}"
REPOSITORY="${REPOSITORY:-chemvantage}"
SERVICE="${SERVICE:-chemvantage-dev}"
IMAGE_TAG="${1:-$(git rev-parse --short HEAD)}"

echo "Deploying dev service ${SERVICE} to project ${PROJECT_ID} (region: ${REGION}, tag: ${IMAGE_TAG})"

gcloud builds submit \
  --config cloudbuild.yaml \
  --substitutions _PROJECT_ID="${PROJECT_ID}",_REGION="${REGION}",_REPOSITORY="${REPOSITORY}",_SERVICE="${SERVICE}",_IMAGE_TAG="${IMAGE_TAG}"

echo "Deployment submitted."
