#!/bin/bash

set -e
umask 0002

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "NA"

if (( $# < 2 )); then
  echo """
USAGE $0 <zBatch> <number of nodes> [hard runtime minutes]

Example:
  $0 1:40 120

Notes:
  wafer 53 rate is roughly ? blocks per node per hour
  1:40 w/140 nodes takes ? hours
  hard runtime of < 60 minutes allows use of as many slots as are available,
  otherwise there is a 3000-slot limit
"""
  exit 1
fi

Z_BATCH="${1}"
N_NODES="${2}"
export RUNTIME=${3:-4320} # default is minutes in 3 days

# --------------------------------------------------------------------
# setup export parameters

N5_PATH="/nrs/hess/data/hess_wafer_53/export/hess_wafer_53d.n5"
TRANSFORM_GROUP="/surface-align/run_20240409_135204/pass12"
DATA_SET_OUTPUT="/wafer-53-align/run_20240409_135204/pass12/s0"

ARGV="\
--n5PathInput ${N5_PATH} \
-i /flat/s070_m104/raw -t 20 -b -21 \
-i /flat/s071_m331/raw -t 20 -b -21 \
-i /flat/s072_m150/raw -t 20 -b -21 \
-i /flat/s073_m079/raw -t 20 -b -21 \
-i /flat/s074_m265/raw -t 20 -b -21 \
-i /flat/s075_m119/raw -t 20 -b -21 \
-i /flat/s076_m033/raw -t 20 -b -21 \
-i /flat/s077_m286/raw -t 20 -b -21 \
-i /flat/s078_m279/raw -t 20 -b -21 \
-i /flat/s079_m214/raw -t 20 -b -21 \
--n5TransformGroup ${TRANSFORM_GROUP} \
--n5PathOutput ${N5_PATH} \
--n5DatasetOutput ${DATA_SET_OUTPUT} \
--zBatch ${Z_BATCH} \
--blockSize=256,256,32"
# --normalizeContrast
# --explainPlan        # use --explainPlan option to output debug info without running export

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