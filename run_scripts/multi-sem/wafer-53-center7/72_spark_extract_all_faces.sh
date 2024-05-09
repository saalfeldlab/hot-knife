#!/bin/bash

set -e

if (( $# < 2 )); then
  echo "USAGE $0 <raw slab> <top nodes> <bottom nodes> (e.g. s071_m331 67 30)"
  exit 1
fi

RAW_SLAB="${1}"

# 60 11-core worker nodes: /flat/s071_m331/top4... (7 mFOVs) invert+normalize took 32 min (likely had fast h06 sapphire rapids nodes)
# 50 11-core worker nodes: /flat/s070_m104/top4... (7 mFOVs) invert took 5 min, invert+normalize took 102 min
# 35 11-core worker nodes: /flat/s071_m331/top4... (7 mFOVs) invert took 6 min
TOP_NODES="${2}"

# 60 11-core worker nodes: /flat/s071_m331/bot4... (7 mFOVs) invert+normalize took 58 min
# 50 11-core worker nodes: /flat/s070_m104/bot4... (7 mFOVs) invert took 4 min, invert+normalize took  92 min
# 35 11-core worker nodes: /flat/s071_m331/bot4... (7 mFOVs) invert took 6 min
BOT_NODES="${3}"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

TOP_SURFACE_DEPTH=4
BOT_SURFACE_DEPTH=4
SURFACE_SIZE=31 # TODO: remove this hardcoding and read from attributes.json in 72_spark_extract_face.sh
COLOR="in" # i, n, in (or nothing)

${SCRIPT_DIR}/72_spark_extract_face.sh ${RAW_SLAB} ${TOP_NODES} top ${TOP_SURFACE_DEPTH} ${SURFACE_SIZE} ${COLOR}
sleep 5
${SCRIPT_DIR}/72_spark_extract_face.sh ${RAW_SLAB} ${BOT_NODES} bot ${BOT_SURFACE_DEPTH} ${SURFACE_SIZE} ${COLOR}
