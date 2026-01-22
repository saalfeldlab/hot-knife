#!/bin/bash

set -euo pipefail

umask 0002

usage() {
  echo "
Usage: $0 <original dir> <sofima dir> <sofima-full dir>

Example:
  $0  pass03  pass03-sofima  pass03-sofima-full
"
  exit 1
}

[[ $# -eq 3 ]] || usage

ORIGINAL_DIR=$(cd "$1" && pwd) || { echo "ERROR: Cannot access source dir: $1"; exit 1; }
SOFIMA_DIR=$(cd "$2" && pwd) || { echo "ERROR: Cannot access sofima dir: $2"; exit 1; }
SOFIMA_FULL_DIR="$3"

if [ -d "${SOFIMA_FULL_DIR}" ]; then
    echo "ERROR: ${SOFIMA_FULL_DIR} already exists"
    exit 1
fi

mkdir -p "${SOFIMA_FULL_DIR}"
chmod 2775 "${SOFIMA_FULL_DIR}"

for ORIGINAL_PATH in "${ORIGINAL_DIR}"/*; do
    NAME="$(basename "${ORIGINAL_PATH}")"
    ln -sfn "${ORIGINAL_PATH}" "${SOFIMA_FULL_DIR}/${NAME}"
done

# Overwrite with links to directories immediately inside SOFIMA_DIR
for SOFIMA_PATH in "${SOFIMA_DIR}"/*; do
    if [[ -d "${SOFIMA_PATH}" ]]; then
        NAME="$(basename "${SOFIMA_PATH}")"
        ln -sfn "${SOFIMA_PATH}" "${SOFIMA_FULL_DIR}/${NAME}"
    fi
done

echo "
${SOFIMA_FULL_DIR}:
"
ls -l "${SOFIMA_FULL_DIR}"
echo