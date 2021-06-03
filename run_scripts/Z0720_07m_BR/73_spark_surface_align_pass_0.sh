#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "${TAB}"

# This runs quickly!  A 1 node job for Sec26 - Sec39 took < 4 minutes to finish.
N_NODES=1

N5_GROUP_OUTPUT="${N5_SURFACE_ROOT}/pass00"

MIN_SEC_NUM=26
MAX_SEC_NUM=39

unset FACE_DATASET_ARGS
for SEC_NUM in $( seq ${MIN_SEC_NUM} ${MAX_SEC_NUM} ); do
  # Face dataset order is important.  For Z0720_07m_BR,
  # Sec38 top connects to Sec39 bot so bottom faces should be listed first.
  FACE_DATASET_ARGS="${FACE_DATASET_ARGS} -d /flat/Sec${SEC_NUM}/bot/face -d /flat/Sec${SEC_NUM}/top/face"
done

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