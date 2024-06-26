#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "$0")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

source "${SCRIPT_DIR}"/../00_config.sh "NA"

SLABS_PER_FILE=31 # 31 slabs should take about 6 hours to complete, 402 / 31 = 13 files
COUNT=0
BATCH_COUNT=0

RUN_DIR="${SCRIPT_DIR}/run_71_flat_$(date +"%Y%m%d_%H%M%S")"
mkdir -p "${RUN_DIR}"

for SLAB in ${ALL_SLABS}; do

  source "${SCRIPT_DIR}"/../00_config.sh "${SLAB}"

  if ! (( COUNT % SLABS_PER_FILE )); then
    BC_VAL=$(printf '%03d' ${BATCH_COUNT})
    CSV_FILE="${RUN_DIR}/batch_${BC_VAL}.csv"
    echo -n "" > "${CSV_FILE}"
    BATCH_COUNT=$((BATCH_COUNT+=1))
  fi

  # /render/slab_000_to_009/s002_m395_align_no35_horiz_avgshd_ic___20240504_084955_norm-layer-clahe/s0
  RAW_DATASET="${N5_ALIGNED_SLAB_DATASET}_norm-layer-clahe/s0"
  if [[ ! -d ${N5_SAMPLE_PATH}${RAW_DATASET} ]]; then
    echo "ERROR: ${N5_SAMPLE_PATH}${RAW_DATASET} does not exist"
    exit 1
  fi

  # /heightfields_fix/slab_000_to_009/s002_m395
  if [[ ! -d ${N5_SAMPLE_PATH}${N5_HEIGHT_FIELDS_FIX_DATASET} ]]; then
    echo "ERROR: ${N5_SAMPLE_PATH}${N5_HEIGHT_FIELDS_FIX_DATASET} does not exist"
    exit 1
  fi

  # /flat_clahe/s002_m395/raw/s0
  OUT_DATASET="${N5_FLAT_DATASET_ROOT}/raw/s0"
  if [[ -d ${N5_SAMPLE_PATH}${OUT_DATASET} ]]; then
    echo "ERROR: ${N5_SAMPLE_PATH}${OUT_DATASET} already exists"
    exit 1
  fi

  echo "${RAW_DATASET},${N5_HEIGHT_FIELDS_FIX_DATASET},${OUT_DATASET}" >> "${CSV_FILE}"

  COUNT=$((COUNT+=1))

done

echo "Created ${RUN_DIR} with:"
ls -alh ${RUN_DIR}/*.csv
