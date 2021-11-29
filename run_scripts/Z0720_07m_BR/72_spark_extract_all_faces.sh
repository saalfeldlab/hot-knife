#!/bin/bash

set -e

if (( $# < 2 )); then
  echo "USAGE $0 <tab id> <number of nodes> (e.g. Sec39 10)"
  exit 1
fi

TAB="${1}"
N_NODES="${2}"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

for SURFACE_DEPTH in 23 13; do

  for TOP_OR_BOTTOM in top bot; do

    ${SCRIPT_DIR}/72_spark_extract_face.sh ${TAB} ${N_NODES} ${TOP_OR_BOTTOM} ${SURFACE_DEPTH}

    # give LSF scheduler a chance to handle previous job
    sleep 5

  done

done