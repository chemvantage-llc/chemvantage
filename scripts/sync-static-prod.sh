#!/usr/bin/env bash
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-chem-vantage-hrd}"
STATIC_BUCKET="${STATIC_BUCKET:-}"
SOURCE_DIR="${SOURCE_DIR:-src/main/webapp}"
EXCLUDE_REGEX="${EXCLUDE_REGEX:-(^|/)\..*}"

if [[ -z "${STATIC_BUCKET}" ]]; then
  echo "STATIC_BUCKET is required for production sync (example: chemvantage-static-prod)." >&2
  exit 1
fi

if [[ ! -d "${SOURCE_DIR}" ]]; then
  echo "Source directory not found: ${SOURCE_DIR}" >&2
  exit 1
fi

echo "Syncing static assets from ${SOURCE_DIR} to gs://${STATIC_BUCKET} (project: ${PROJECT_ID})"
gcloud storage rsync "${SOURCE_DIR}" "gs://${STATIC_BUCKET}" \
  --project="${PROJECT_ID}" \
  --recursive \
  --exclude="${EXCLUDE_REGEX}"

echo "Static sync complete."
