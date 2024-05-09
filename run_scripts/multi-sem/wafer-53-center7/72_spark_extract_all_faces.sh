#!/bin/bash

set -e

if (( $# < 2 )); then
  echo "USAGE $0 <raw slab> <nodes> (e.g. s071_m331 60)"
  exit 1
fi

RAW_SLAB="${1}"

# 100 all slow 11-core worker nodes:                     /flat/s070_m104/bot5... (7 mFOVs) invert+normalize took  69 min
#  60 all fast h06 sapphire rapids 11-core worker nodes: /flat/s071_m331/top4... (7 mFOVs) invert+normalize took  32 min
#  60 mostly/all slow 11-core worker nodes:              /flat/s071_m331/bot4... (7 mFOVs) invert+normalize took  58 min
#  50 mostly/all slow 11-core worker nodes:              /flat/s070_m104/top4... (7 mFOVs) invert+normalize took 102 min
#  50 mostly/all slow 11-core worker nodes:              /flat/s070_m104/top4... (7 mFOVs) invert           took   5 min
#  35 mostly/all slow 11-core worker nodes:              /flat/s071_m331/top4... (7 mFOVs) invert           took   6 min
N_NODES="${2}"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "${RAW_SLAB}"

SLAB_PROJECT=$(getSlabProjectName "${RAW_SLAB}")

# /nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5/heightfields_fix/slab_070_to_079/s070_m104/max/attributes.json
HF_FIX_MAX_ATTR_FILE="${N5_SAMPLE_PATH}/heightfields_fix/${SLAB_PROJECT}/${RAW_SLAB}/max/attributes.json"
if [[ ! -f ${HF_FIX_MAX_ATTR_FILE} ]]; then
  echo "ERROR: missing file ${HF_FIX_MAX_ATTR_FILE}"
  exit 1
fi

#{
#  "dataType": "float32", "compression": { "type": "gzip", "useZlib": false, "level": -1 },
#  "blockSize": [ 1024, 1024 ],
#  "dimensions": [ 26497, 26072 ],
#  "avg": 33.3189829188907,
#  "downsamplingFactors": [ 2, 2, 1 ]
#}
AVG_SIZE=$(/groups/flyem/data/render/bin/jq '. .avg | tonumber | floor' "${HF_FIX_MAX_ATTR_FILE}")
SURFACE_SIZE=$((AVG_SIZE - 2))

TOP_SURFACE_DEPTH=4
BOT_SURFACE_DEPTH=4
COLOR="in" # i, n, in (or nothing)

${SCRIPT_DIR}/72_spark_extract_face.sh ${RAW_SLAB} ${N_NODES} top ${TOP_SURFACE_DEPTH} ${SURFACE_SIZE} ${COLOR}
sleep 5
${SCRIPT_DIR}/72_spark_extract_face.sh ${RAW_SLAB} ${N_NODES} bot ${BOT_SURFACE_DEPTH} ${SURFACE_SIZE} ${COLOR}
