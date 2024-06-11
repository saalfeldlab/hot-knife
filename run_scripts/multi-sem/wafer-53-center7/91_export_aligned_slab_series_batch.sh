#!/bin/bash

set -e
umask 0002

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "NA"

# TODO: update export times in notes for wafer-53-center7 (times here are for seminar wafer-53d)
# TODO: test zBatch distribution (only used 1:1 for seminar wafer-53d because we only had 20 slabs)

if (( $# < 2 )); then
  echo """
USAGE $0 <zBatch> <number of nodes> [hard runtime minutes]

Example:
  $0 1:1 272

Notes:
  export of 10 wafer 53 slabs using max 272 11-slot worker nodes took 26 minutes
  export of 20 wafer 53 slabs using max 272 11-slot worker nodes took 46 minutes

  hard runtime of < 60 minutes allows use of as many slots as are available,
  otherwise there is a 3000-slot limit
"""
  exit 1
fi

Z_BATCH="${1}"
N_NODES="${2}"
export RUNTIME=${3:-240:59} # default is 10+ days

# --------------------------------------------------------------------
# setup export parameters

RUN_AND_PASS="run_20240609_070000/pass12"
TRANSFORM_GROUP="/surface-align/${RUN_AND_PASS}"
DATA_SET_OUTPUT="/wafer-53-align/${RUN_AND_PASS}/s0"

ARGV="--n5PathInput ${N5_SAMPLE_PATH} \
--n5TransformGroup ${TRANSFORM_GROUP} \
--n5PathOutput ${N5_SAMPLE_PATH} \
--n5DatasetOutput ${DATA_SET_OUTPUT} \
--zBatch ${Z_BATCH} \
--blockSize=256,256,128"
# --normalizeContrast
# --explainPlan        # use --explainPlan option to output debug info without running export

for SLAB in ${ALL_SLABS}; do
  ARGV="${ARGV} -i /flat_clahe/${SLAB}/raw -t 3 -b -4"
done

CLASS="org.janelia.saalfeldlab.hotknife.SparkExportAlignedSlabSeries"

# --------------------------------------------------------------------
# TODO: need to update this for wafer 53 once we've tried a couple of options ...

# To distribute work evenly, recommended/default number of tasks/partitions is 3 times the number of cores.
#export N_TASKS_PER_EXECUTOR_CORE=3

# changed wafer 52 default tasks per core from 3 to 12 based upon ...
#   with zBatch 1:40, each wafer 52 batch contains 72,216 blocks
#   total tasks = worker nodes * active cores per worker * tasks per core = 100 nodes * 10 active cores * 12 = 12,000 tasks
#   72,216 blocks / 12,000 tasks = roughly 6 blocks per task
#
# 6 blocks per task and more tasks should help with balancing
# setup will also still work for smaller woker node clusters (e.g. a 25 node cluster would have 24 blocks per task)
# only need to be careful for larger node clusters since you want to ensure multiple blocks per task
export N_TASKS_PER_EXECUTOR_CORE=12

LOG_FILE=$(setupRunLog "export-aligned-slab-series-batch")

# use shell group to tee all output to log file
{

  echo """
Running with arguments:
${ARGV}
"""
  # shellcheck disable=SC2086
  ${FLINTSTONE} ${N_NODES} "${HOT_KNIFE_JAR}" ${CLASS} ${ARGV}

} 2>&1 | tee -a "${LOG_FILE}"
