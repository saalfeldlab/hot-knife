#!/bin/bash

set -euo pipefail

umask 0002

usage() {
  echo "
Usage: $0 SOURCE_DIR TARGET_DIR

Examples:
  $0  pass03         pass03-sofima-fill
  $0  pass03-sofima  pass03-sofima-fill
"
  exit 1
}

[[ $# -eq 2 ]] || usage

SOURCE_DIR=$(cd "$1" && pwd) || { echo "ERROR: Cannot access source dir: $1"; exit 1; }
TARGET_DIR=$2

mkdir -p "$TARGET_DIR"
TARGET_DIR=$(cd "${TARGET_DIR}" && pwd) || { echo "ERROR: Cannot access/create target dir: $2"; exit 1; }

echo "
Setting up links in: ${TARGET_DIR}
to files in:         ${SOURCE_DIR}
"

LINK_COUNT=0

# Walk all files under source, preserving relative paths in target.
# The -print0 parameter keeps it safe for special characters.
while IFS= read -r -d '' f; do

  RELATIVE_PATH_IN_SOURCE_DIR=${f#"${SOURCE_DIR}"/}
  FULL_TARGET_PATH="${TARGET_DIR}/${RELATIVE_PATH_IN_SOURCE_DIR}"

  # Ensure parent directories exist in target
  mkdir -p "$(dirname "${FULL_TARGET_PATH}")"

  # Overwrite existing destination path if needed.
  # ln -sfn: -s symlink, -f force remove destination, -n treat dest symlink as file
  ln -sfn "$f" "${FULL_TARGET_PATH}"

  ((++LINK_COUNT))
  if (( LINK_COUNT % 1000 == 0 )); then
    echo "created ${LINK_COUNT} links ..."
  fi

done < <(find "${SOURCE_DIR}" -type f -print0)

echo "created ${LINK_COUNT} links in total
"
