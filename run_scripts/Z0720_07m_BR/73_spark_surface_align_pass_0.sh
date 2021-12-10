#!/bin/bash

set -e

if (( $# != 2)); then
  echo "USAGE: $0 <min tab number> <max tab number>"
  exit 1
fi

# needs to be exported so that 00_config picks it up for N5_SURFACE_ROOT definition
export MIN_SEC_NUM="$1"
export MAX_SEC_NUM="$2"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "tab_not_applicable"

# This runs quickly!  A 1 node job for Sec26 - Sec39 took < 4 minutes to finish.
N_NODES=1

N5_GROUP_OUTPUT="${N5_SURFACE_ROOT}/pass00"

# Face dataset order is important.
# For Z0720_07m_BR, tabs need to be in reverse order (40 down to 6)
# and bot of greater tab gets connected to top of lesser tab (e.g. Sec40 bot connects to Sec39 top).
unset FACE_DATASET_ARGS
for SEC_NUM in $( seq ${MAX_SEC_NUM} -1 ${MIN_SEC_NUM} ); do
  PADDED_SEC_NUM=$(printf %02d "${SEC_NUM}")
  FACE_DATASET_ARGS="${FACE_DATASET_ARGS} -d /flat/Sec${PADDED_SEC_NUM}/top/face -d /flat/Sec${PADDED_SEC_NUM}/bot/face"
done

# Ensure prior Sec26 to Sec39 sub-volume alignment stays fixed and align adjacent tabs to the fixed sub-volume
unset FIXED_ARGS
BOUNDS_ARGS="--boundsMin=-4290.1896659691274,-7049.9539956412625 --boundsMax=55524.957376094775,66299.9177343185"
if [[ "${MAX_SEC_NUM}" == "26" ]]; then
  # Sec26 AffineTransform from /groups/flyem/data/trautmane/spark_logs/20210525_101141/logs/04-driver.log
  FIXED_MODEL="'[[0.976458134610771, -0.07130354456487, 2067.2702116819314], [0.049932014157421, 1.02615875848036, -652.1312879684971]]'"
  FIXED_ARGS="${BOUNDS_ARGS} -f /flat/Sec26/bot/face -fm ${FIXED_MODEL} -f /flat/Sec26/top/face -fm ${FIXED_MODEL}"
elif [[ "${MIN_SEC_NUM}" == "39" ]]; then
  # shellcheck disable=SC2089
  # Sec39 AffineTransform from /groups/flyem/data/trautmane/spark_logs/20210525_101141/logs/04-driver.log
  FIXED_MODEL="'[[0.965177672396418, -0.204446406063681, 25052.735188858624], [0.268000751437709, 0.961977006266506, 15807.754844432804]]'"
  FIXED_ARGS="${BOUNDS_ARGS} -f /flat/Sec39/bot/face -fm ${FIXED_MODEL} -f /flat/Sec39/top/face -fm ${FIXED_MODEL}"
fi

FACE_DATASET_ARGS="${FACE_DATASET_ARGS} ${FIXED_ARGS}"

ARGV="
--n5Path=${N5_SAMPLE_PATH} \
--n5GroupOutput=${N5_GROUP_OUTPUT} \
--scaleIndex=4 \
${FACE_DATASET_ARGS}"

CLASS="org.janelia.saalfeldlab.hotknife.SparkAlignAffineGlobal"

LOG_FILE=$(setupRunLog "surface-align-pass00")

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
  n5-view.sh -i ${N5_SAMPLE_PATH} -d ${N5_GROUP_OUTPUT}
"

} 2>&1 | tee -a "${LOG_FILE}"