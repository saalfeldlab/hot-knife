#!/bin/bash

set -e

if (( $# < 5 )); then
  echo "USAGE $0 <raw slab> <number of nodes> <top|bot> <abs depth> <size> [color] (e.g. s071_m331 5 top 31 4 31 in)"
  exit 1
fi

RAW_SLAB="${1}"
N_NODES="${2}" # wafer 52 cut_035_slab_001 top 20 took 2 minutes with 15 nodes
TOP_OR_BOTTOM="${3}"
SURFACE_DEPTH="${4}"
SURFACE_SIZE="${5}" # TODO: read this from ${FULL_FACE_DATASET_PATH}/attributes.json: int(avg) - 2
COLOR="${6}"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "${RAW_SLAB}"

validateDirectoriesExist "${N5_SAMPLE_PATH}${N5_FLAT_RAW_DATASET}"

FACE_BASE_NAME="${TOP_OR_BOTTOM}${SURFACE_DEPTH}${COLOR}"

N5_FACE_DATASET="${N5_FLAT_DATASET_ROOT}/${FACE_BASE_NAME}"

FULL_FACE_DATASET_PATH="${N5_SAMPLE_PATH}${N5_FACE_DATASET}"
if [[ -d ${FULL_FACE_DATASET_PATH} ]]; then
  echo "ERROR: ${FULL_FACE_DATASET_PATH} exists"
  exit 1
fi

case "${TOP_OR_BOTTOM}" in
  "top")
    MIN="0,0,${SURFACE_DEPTH}"
    SIZE="0,0,${SURFACE_SIZE}"
  ;;
  "bot")
    MIN="0,0,-${SURFACE_DEPTH}"
    SIZE="0,0,-${SURFACE_SIZE}"
  ;;
  *)
    echo "ERROR: 'location parameter ${TOP_OR_BOTTOM} must be 'top' or 'bot'"
    exit 1
  ;;
esac

COLOR_ARGS=""
case "${COLOR}" in
  "i") COLOR_ARGS="--invert" ;;
  "n") COLOR_ARGS="--normalizeContrast" ;;
  "in") COLOR_ARGS="--invert --normalizeContrast" ;;
esac

ARGV="\
--n5Path=${N5_SAMPLE_PATH} \
--n5DatasetInput=${N5_FLAT_RAW_DATASET} \
--n5GroupOutput=${N5_FACE_DATASET} \
--min=${MIN} \
--size=${SIZE} ${COLOR_ARGS} \
--blockSize=1024,1024"

CLASS="org.janelia.saalfeldlab.hotknife.SparkGenerateFaceScaleSpace"

LOG_FILE=$(setupRunLog "gen-face")

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
  n5-view.sh -i ${N5_SAMPLE_PATH} -d ${N5_FACE_DATASET}/face
"

} 2>&1 | tee -a "${LOG_FILE}"
