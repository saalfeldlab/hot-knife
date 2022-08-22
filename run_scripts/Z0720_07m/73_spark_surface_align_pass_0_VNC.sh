#!/bin/bash

set -e

# needs to be exported so that 00_config picks it up for N5_SURFACE_ROOT definition
export MIN_SEC_NUM="6"
export MAX_SEC_NUM="36"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "tab_not_applicable"

# This runs quickly!  A 1 node job for Sec26 - Sec39 took < 4 minutes to finish.
N_NODES=1

N5_GROUP_OUTPUT="${N5_SURFACE_ROOT}/pass00"

# Face dataset order is important.
# For Z0720_07m_VNC, tabs need to be in reverse order (36 down to 6)
# and bot of greater tab gets connected to top of lesser tab (e.g. Sec36 bot connects to Sec35 top).
unset FACE_DATASET_ARGS
for SEC_NUM in $( seq ${MAX_SEC_NUM} -1 ${MIN_SEC_NUM} ); do
  PADDED_SEC_NUM=$(printf %02d "${SEC_NUM}")
  FACE_DATASET_ARGS="${FACE_DATASET_ARGS} -d /flat/Sec${PADDED_SEC_NUM}/top/face -d /flat/Sec${PADDED_SEC_NUM}/bot/face"
done

# Ensure Sec36 alignment stays fixed and align adjacent tabs to the fixed sub-volume
unset FIXED_ARGS BOUNDS_ARGS
#BOUNDS_ARGS="--boundsMin=TBD --boundsMax=TBD"

# shellcheck disable=SC2089
FIXED_MODEL="'[[1.0, 0.0, 0.0], [0.0, 1.0, 0.0]]'"
#TODO: fix here!
FIXED_ARGS="${BOUNDS_ARGS} -f /flat/Sec36/bot/face -fm ${FIXED_MODEL} -f /flat/Sec36/top/face -fm ${FIXED_MODEL}"


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