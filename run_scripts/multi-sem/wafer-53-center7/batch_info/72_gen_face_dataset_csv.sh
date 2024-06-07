#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "$0")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

source "${SCRIPT_DIR}"/../00_config.sh "NA"

SLABS_PER_FILE=31 # ? slabs should take about ? hours to complete, 402 / 31 = 13 files
SURFACE_DEPTH=4
COLOR="i" # i (invert), n (normalize), in (invert+normalize), or nothing
FACE_SUFFIX="${SURFACE_DEPTH}${COLOR}"

COUNT=0
BATCH_COUNT=0

RUN_DIR="${SCRIPT_DIR}/run_72_face_$(date +"%Y%m%d_%H%M%S")"
mkdir -p "${RUN_DIR}"

for SLAB in ${ALL_SLABS}; do

  source "${SCRIPT_DIR}"/../00_config.sh "${SLAB}"

  if ! (( COUNT % SLABS_PER_FILE )); then
    BC_VAL=$(printf '%03d' ${BATCH_COUNT})
    CSV_FILE="${RUN_DIR}/batch_${BC_VAL}.csv"
    echo -n "" > "${CSV_FILE}"
    BATCH_COUNT=$((BATCH_COUNT+=1))
  fi

  # /flat_clahe/s036_m252/raw/s0
  RAW_DATASET="${N5_FLAT_DATASET_ROOT}/raw/s0"
  if [[ ! -d ${N5_SAMPLE_PATH}${OUT_DATASET} ]]; then
    echo "ERROR: ${N5_SAMPLE_PATH}${OUT_DATASET} does not exist"
    exit 1
  fi

  # /flat_clahe/s036_m252/top4i
  TOP_DATASET="${N5_FLAT_DATASET_ROOT}/top${FACE_SUFFIX}"
  if [[ -d ${N5_SAMPLE_PATH}${TOP_DATASET} ]]; then
    echo "ERROR: ${N5_SAMPLE_PATH}${TOP_DATASET} already exists"
    exit 1
  fi

  # /flat_clahe/s036_m252/bot4i
  BOT_DATASET="${N5_FLAT_DATASET_ROOT}/bot${FACE_SUFFIX}"
  if [[ -d ${N5_SAMPLE_PATH}${BOT_DATASET} ]]; then
    echo "ERROR: ${N5_SAMPLE_PATH}${BOT_DATASET} already exists"
    exit 1
  fi

  SLAB_PROJECT=$(getSlabProjectName "${SLAB}")

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

  echo "${RAW_DATASET},${TOP_DATASET},${SURFACE_DEPTH},${SURFACE_SIZE}" >> "${CSV_FILE}"
  echo "${RAW_DATASET},${BOT_DATASET},-${SURFACE_DEPTH},-${SURFACE_SIZE}" >> "${CSV_FILE}"

  COUNT=$((COUNT+=1))

done

echo "Created ${RUN_DIR} with:"
ls -alh ${RUN_DIR}/*.csv
