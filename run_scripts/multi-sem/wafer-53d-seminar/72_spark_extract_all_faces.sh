#!/bin/bash

set -e

if (( $# < 2 )); then
  echo "USAGE $0 <raw slab> <top nodes> <bottom nodes> (e.g. s071_m331 67 30)"
  exit 1
fi

RAW_SLAB="${1}"
TOP_NODES="${2}" # /flat/s071_m331/top22_icn3 took 152 minutes with 50 nodes
BOT_NODES="${3}" # /flat/s071_m331/bot22_icn3 took 64 minutes with 50 nodes

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

TOP_SURFACE_DEPTH=22
BOT_SURFACE_DEPTH=22

${SCRIPT_DIR}/72_spark_extract_face.sh ${RAW_SLAB} ${TOP_NODES} top ${TOP_SURFACE_DEPTH}
sleep 5
${SCRIPT_DIR}/72_spark_extract_face.sh ${RAW_SLAB} ${BOT_NODES} bot ${BOT_SURFACE_DEPTH}
