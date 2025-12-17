#!/bin/bash

# ----------------------------------------------------------------------------
# Copy the hotknife jar to Google Cloud Storage.
#
# The hotknife jar file is expected to be in the target directory of the hotknife project.
# The jar file is copied to gs://janelia-spark-test/library.

set -e

echo "
running $0 at $(date)
"

BASE_GIT_DIR="${1:-/Users/trautmane/projects/git}"

HOTKNIFE_JAR_FILE_NAME="hot-knife-0.0.7-SNAPSHOT.jar"
BASE_GOOGLE_BUCKET_DIR="gs://janelia-spark-test/library"

# --------------------------
FULL_HOTKNIFE_JAR_PATH="${BASE_GIT_DIR}/hot-knife/target/${HOTKNIFE_JAR_FILE_NAME}"
GS_HOTKNIFE_JAR_URL="${BASE_GOOGLE_BUCKET_DIR}/${HOTKNIFE_JAR_FILE_NAME}"

if gsutil ls "${GS_HOTKNIFE_JAR_URL}"; then
  read -p "${GS_HOTKNIFE_JAR_URL} already exists. Do you want to overwrite it? (y/n) " -n 1 -r
  echo
  if [[ "$REPLY" == [yY] ]]; then
    gsutil cp "${FULL_HOTKNIFE_JAR_PATH}" "${GS_HOTKNIFE_JAR_URL}"
  fi
fi