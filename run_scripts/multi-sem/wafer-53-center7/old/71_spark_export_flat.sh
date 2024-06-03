#!/bin/bash

set -e

if (( $# != 2 )); then
  echo "USAGE $0 <raw slab> <number of nodes> (e.g. s070_m104 10)"
  exit 1
fi

RAW_SLAB="${1}"
N_NODES="${2}" # wafer_53_center7: slab s251 took 49 minutes with 3 nodes, slab s402 took 19 minutes with 9 nodes

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "${RAW_SLAB}"

N5_RAW_S0_DATASET="${N5_ALIGNED_SLAB_DATASET}_norm-layer-clahe/s0"
validateDirectoriesExist "${N5_SAMPLE_PATH}${N5_RAW_S0_DATASET}" "${N5_SAMPLE_PATH}${N5_HEIGHT_FIELDS_FIX_DATASET}"

FULL_FLAT_DATASET_PATH="${N5_SAMPLE_PATH}${N5_FLAT_RAW_DATASET}"
if [[ -d ${FULL_FLAT_DATASET_PATH} ]]; then
  echo "
ERROR: ${FULL_FLAT_DATASET_PATH} exists

For runs after new height field fixes, move the existing data to be deleted like this:
  mv ${N5_SAMPLE_PATH}${N5_HEIGHT_FIELDS_FIX_DATASET} /nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5/delete_me
"
  exit 1
fi

"${SCRIPT_DIR}"/add_heightfields_factors.sh "${RAW_SLAB}" min "${N5_HEIGHT_FIELDS_DOWNSAMPLING_FACTORS}"
"${SCRIPT_DIR}"/add_heightfields_factors.sh "${RAW_SLAB}" max "${N5_HEIGHT_FIELDS_DOWNSAMPLING_FACTORS}"

ARGV="\
--n5RawPath=${N5_SAMPLE_PATH} \
--n5FieldPath=${N5_SAMPLE_PATH} \
--n5OutputPath=${N5_SAMPLE_PATH} \
--n5RawDataset=${N5_RAW_S0_DATASET} \
--n5FieldGroup=${N5_HEIGHT_FIELDS_FIX_DATASET} \
--n5OutDataset=${N5_FLAT_RAW_DATASET} \
--padding=3 \
--multiSem \
--blockSize=128,128,64"

CLASS="org.janelia.saalfeldlab.hotknife.SparkExportFlattenedVolume"

LOG_FILE=$(setupRunLog "export-flat-${SLAB}" logs/71_flat)

# use shell group to tee all output to log file
{

  echo "
Running with arguments:
${ARGV}
"
  # shellcheck disable=SC2086
  ${FLINTSTONE} ${N_NODES} "${HOT_KNIFE_JAR}" ${CLASS} ${ARGV}

  echo "
When completed, view n5 using:
  n5-view.sh -i ${N5_SAMPLE_PATH} -d ${N5_FLAT_RAW_DATASET}
"
} 2>&1 | tee -a "${LOG_FILE}"
