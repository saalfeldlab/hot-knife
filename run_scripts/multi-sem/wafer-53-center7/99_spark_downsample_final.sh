#!/bin/bash

set -e

if (( $# != 1 )); then
  echo "USAGE $0 <number of nodes>"
  exit 1
fi

N_NODES="${1}"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "ALL"

INPUT_DATASET_ROOT="/wafer-53-align/run_20240609_070000/pass12"

OUTPUT_DATASET_PATHS="${INPUT_DATASET_ROOT}/s1"
FACTORS="2,2,2"
for scale in $(seq 2 9); do
  OUTPUT_DATASET_PATHS="${OUTPUT_DATASET_PATHS} ${INPUT_DATASET_ROOT}/s${scale}"
  FACTORS="${FACTORS} 2,2,2"
done

ARGV="\
--n5Path=${N5_SAMPLE_PATH} \
--inputDatasetPath=${INPUT_DATASET_ROOT}/s0 \
--outputDatasetPath=${OUTPUT_DATASET_PATHS} \
--factors=${FACTORS}"

CLASS="org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark"

LOG_FILE=$(setupRunLog "downsample-flat-ALL")

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
  n5-view.sh -i ${N5_SAMPLE_PATH} -d ${INPUT_DATASET_ROOT}
"
} 2>&1 | tee -a "${LOG_FILE}"
