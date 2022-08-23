#!/bin/bash

set -e

if (( $# < 3 )); then
  echo "USAGE $0 <tab id> <number of nodes> <top|bot> [abs depth] (e.g. Sec39 10 bot or Sec07 10 bot 13)"
  exit 1
fi

TAB="${1}"
N_NODES="${2}"
TOP_OR_BOTTOM="${3}"
SURFACE_DEPTH="${4:-23}"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "${TAB}"

validateDirectoriesExist "${N5_SAMPLE_PATH}${N5_FLAT_RAW_DATASET}"

if (( SURFACE_DEPTH == 23 )); then
  FACE_BASE_NAME="${TOP_OR_BOTTOM}"
else
  FACE_BASE_NAME="${TOP_OR_BOTTOM}${SURFACE_DEPTH}"
fi

N5_FACE_DATASET="${N5_FLAT_DATASET_ROOT}/${FACE_BASE_NAME}"

FULL_FACE_DATASET_PATH="${N5_SAMPLE_PATH}${N5_FACE_DATASET}"
if [[ -d ${FULL_FACE_DATASET_PATH} ]]; then
  echo "ERROR: ${FULL_FACE_DATASET_PATH} exists"
  exit 1
fi

case "${TOP_OR_BOTTOM}" in
  "top")
    MIN="0,0,${SURFACE_DEPTH}"
    SIZE="0,0,512"
  ;;
  "bot")
    MIN="0,0,-${SURFACE_DEPTH}"
    SIZE="0,0,-512"
  ;;
  *)
    echo "ERROR: 'location parameter ${TOP_OR_BOTTOM} must be 'top' or 'bot'"
    exit 1
  ;;
esac

ARGV="\
--n5Path=${N5_SAMPLE_PATH} \
--n5DatasetInput=${N5_FLAT_RAW_DATASET} \
--n5GroupOutput=${N5_FACE_DATASET} \
--min=${MIN} \
--size=${SIZE} \
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