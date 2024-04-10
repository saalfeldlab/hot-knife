#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "tab_not_applicable"

# This runs quickly!  A 1 node job for 10 slabs in wafer 53 took 4 minutes to finish.
# One slab per slot
N_NODES=1
SLAB_LIST="
s070_m104 s071_m331 s072_m150 s073_m079 s074_m265 s075_m119 s076_m033 s077_m286 s078_m279 s079_m214
s080_m174 s081_m049 s082_m190 s083_m029 s084_m069 s085_m031 s086_m181 s087_m155 s088_m291 s089_m045
"
N5_GROUP_OUTPUT="${N5_SURFACE_ROOT}/pass00"

# Face dataset order is important.
unset FACE_DATASET_ARGS
for SLAB in ${SLAB_LIST}; do
  FACE_DATASET_ARGS="${FACE_DATASET_ARGS} -d /flat/${SLAB}/top22_icn3/face -d /flat/${SLAB}/bot22_icn3/face"
done

# need scaleIndex=5 for larger wafer 53 slabs
ARGV="
--n5Path=${N5_SAMPLE_PATH} \
--n5GroupOutput=${N5_GROUP_OUTPUT} \
--scaleIndex=5 \
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
