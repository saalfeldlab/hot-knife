#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "$0")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

source "${SCRIPT_DIR}"/../00_config.sh "NA"

SLABS_PER_FILE=35
COUNT=0

for SLAB in ${ALL_SLABS}; do

  source "${SCRIPT_DIR}"/../00_config.sh "${SLAB}"

  if ! (( COUNT % SLABS_PER_FILE )); then
    C_VAL=$(printf '%05d' ${COUNT})
    CSV_FILE="masked_clahe_dataset.${C_VAL}.csv"
    echo -n "" > "${CSV_FILE}"
  fi

  DATASET_INPUT="${N5_ALIGNED_SLAB_DATASET}_norm-layer/s0"
  if [[ ! -d ${N5_SAMPLE_PATH}${DATASET_INPUT} ]]; then
    echo "ERROR: ${N5_SAMPLE_PATH}${DATASET_INPUT} does not exist"
    exit 1
  fi

  DATASET_OUTPUT="${N5_ALIGNED_SLAB_DATASET}_norm-layer-clahe/s0"
  if [[ -d ${N5_SAMPLE_PATH}${DATASET_OUTPUT} ]]; then
    echo "ERROR: ${N5_SAMPLE_PATH}${DATASET_OUTPUT} already exists"
    exit 1
  fi

  FIELD_MAX="${N5_HEIGHT_FIELDS_FIX_DATASET}/max"
  if [[ ! -d ${N5_SAMPLE_PATH}${FIELD_MAX} ]]; then
    echo "ERROR: ${N5_SAMPLE_PATH}${FIELD_MAX} does not exist"
    exit 1
  fi

  echo "${DATASET_INPUT},${DATASET_OUTPUT},${FIELD_MAX}" >> "${CSV_FILE}"

  COUNT=$((COUNT+=1))

done

ls -alh masked_clahe_dataset.*.csv
