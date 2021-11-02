#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "${TAB}"

# This runs quickly!  A 1 node job for Sec26 - Sec39 took < 4 minutes to finish.
N_NODES=1

N5_GROUP_OUTPUT="${N5_SURFACE_ROOT}/pass00"

MIN_SEC_NUM=6
MAX_SEC_NUM=25

unset FACE_DATASET_ARGS
for SEC_NUM in $( seq ${MIN_SEC_NUM} ${MAX_SEC_NUM} ); do
  PADDED_SEC_NUM=$(printf %02d "${SEC_NUM}")
  # Face dataset order is important.  For Z0720_07m_BR,
  # Sec38 top connects to Sec39 bot so bottom faces should be listed first.
  FACE_DATASET_ARGS="${FACE_DATASET_ARGS} -d /flat/Sec${PADDED_SEC_NUM}/bot/face -d /flat/Sec${PADDED_SEC_NUM}/top/face"
done

# Ensure prior Sec26 to Sec39 sub-volume alignment stays fixed and align adjacent tabs to the fixed sub-volume

# solve models pulled from AffineTransform lines in /groups/flyem/data/trautmane/spark_logs/20210525_101141/logs/04-driver.log
# shellcheck disable=SC2089
MODEL_26="'[[0.976458134610771, -0.07130354456487, 2067.2702116819314], [0.049932014157421, 1.02615875848036, -652.1312879684971]]'"
MODEL_39="'[[0.965177672396418, -0.204446406063681, 25052.735188858624], [0.268000751437709, 0.961977006266506, 15807.754844432804]]'"

FACE_DATASET_ARGS="${FACE_DATASET_ARGS} \
-f /flat/Sec26/bot/face -fm ${MODEL_26} -f /flat/Sec26/top/face -fm ${MODEL_26} \
-f /flat/Sec39/bot/face -fm ${MODEL_39} -f /flat/Sec39/top/face -fm ${MODEL_39} \
-d /flat/Sec40/bot/face -d /flat/Sec40/top/face"

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