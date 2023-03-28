#!/bin/bash

set -e

if (( $# != 2 )); then
  echo "USAGE $0 <cut and slab> <number of nodes> (e.g. cut_036_slab_045 10)"
  exit 1
fi

CUT_AND_SLAB="${1}"
N_NODES="${2}"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "${CUT_AND_SLAB}"

validateDirectoriesExist "${N5_SAMPLE_PATH}${N5_FLAT_RAW_DATASET}"
N5_FLAT_RAW_DATASET_PARENT=$(dirname "${N5_FLAT_RAW_DATASET}")

OUTPUT_DATASET_PATH="${N5_FLAT_DATASET_ROOT}/raw/s1"
FACTORS="2,2,1"
for scale in $(seq 2 9); do
  OUTPUT_DATASET_PATH="${OUTPUT_DATASET_PATH} ${N5_FLAT_DATASET_ROOT}/raw/s${scale}"
  FACTORS="${FACTORS} 2,2,1"
done

ARGV="\
--n5Path=${N5_SAMPLE_PATH} \
--inputDatasetPath=${N5_FLAT_RAW_DATASET} \
--outputDatasetPath=${OUTPUT_DATASET_PATH} \
--factors=${FACTORS}"

CLASS="org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark"

LOG_FILE=$(setupRunLog "downsample-flat-${CUT_AND_SLAB}")

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
  n5-view.sh -i ${N5_SAMPLE_PATH} -d ${N5_FLAT_RAW_DATASET_PARENT}
"
} 2>&1 | tee -a "${LOG_FILE}"