#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "tab_not_applicable"

# This runs quickly!  A 1 node job for 7 slabs in wafer 52 took 4 minutes to finish.
N_NODES=1

N5_GROUP_OUTPUT="${N5_SURFACE_ROOT}/pass00"

# Face dataset order is important.
unset FACE_DATASET_ARGS
for CUT in cut_030_slab_026 cut_031_slab_006 cut_032_slab_013 cut_033_slab_033 cut_034_slab_020 cut_035_slab_001 cut_036_slab_045 ; do
  FACE_DATASET_ARGS="${FACE_DATASET_ARGS} -d /flat/${CUT}/top22/face -d /flat/${CUT}/bot22/face"
done

ARGV="
--n5Path=${N5_SAMPLE_PATH} \
--n5GroupOutput=${N5_GROUP_OUTPUT} \
--scaleIndex=4 \
--iterations 100000 \
--maxError 400 \
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