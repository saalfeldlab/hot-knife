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
  $0 1:40 70 239

Notes:
  wafer 52 rate is roughly ? blocks per node per hour
  1:40 w/70 nodes takes ? hours
  hard runtime of < 240 minutes allows use of GPU nodes (and theoretically burst nodes)
"""
  exit 1
fi

Z_BATCH="${1}"
N_NODES="${2}"
export RUNTIME=${3:-4320} # default is minutes in 3 days

# --------------------------------------------------------------------
# setup export parameters

N5_PATH="/nrs/hess/render/export/hess.n5"
TRANSFORM_GROUP="/surface-align/run_20230330_092301/pass03"
DATA_SET_OUTPUT="/wafer-52-align/run_20230330_092301/pass03/s0"

ARGV="\
--n5PathInput ${N5_PATH} \
-i /flat/cut_030_slab_026/raw -t 20 -b -21 \
-i /flat/cut_031_slab_006/raw -t 20 -b -21 \
-i /flat/cut_032_slab_013/raw -t 20 -b -21 \
-i /flat/cut_033_slab_033/raw -t 20 -b -21 \
-i /flat/cut_034_slab_020/raw -t 20 -b -21 \
-i /flat/cut_035_slab_001/raw -t 20 -b -21 \
-i /flat/cut_036_slab_045/raw -t 20 -b -21 \
--n5TransformGroup ${TRANSFORM_GROUP} \
--n5PathOutput ${N5_PATH} \
--n5DatasetOutput ${DATA_SET_OUTPUT} \
--zBatch ${Z_BATCH} \
--blockSize=256,256,32"
# --normalizeContrast
# --explainPlan        # use --explainPlan option to output debug info without running export

#COMMIT="db085854100f84ec9de178b1ac0ea2ddfa60ab03"
#HOT_KNIFE_JAR="/groups/flyem/data/render/lib/hot-knife-0.0.4-${COMMIT}-SNAPSHOT.jar"
HOT_KNIFE_JAR="/groups/flyem/data/render/lib/hot-knife.latest-fat.jar"
CLASS="org.janelia.saalfeldlab.hotknife.SparkExportAlignedSlabSeries"

# --------------------------------------------------------------------
# setup spark parameters (11 cores per worker)

export N_EXECUTORS_PER_NODE=2
export N_CORES_PER_EXECUTOR=5
export N_OVERHEAD_CORES_PER_WORKER=1
# Note: N_CORES_PER_WORKER=$(( (N_EXECUTORS_PER_NODE * N_CORES_PER_EXECUTOR) + N_OVERHEAD_CORES_PER_WORKER ))

# To distribute work evenly, recommended number of tasks/partitions is 3 times the number of cores.
#export N_TASKS_PER_EXECUTOR_CORE=3

# changed default tasks per core from 3 to 12 based upon ...
#   with zBatch 1:40, each batch contains 72,216 blocks
#   total tasks = worker nodes * active cores per worker * tasks per core = 100 nodes * 10 active cores * 12 = 12,000 tasks
#   72,216 blocks / 12,000 tasks = roughly 6 blocks per task
#
# 6 blocks per task and more tasks should help with balancing
# setup will also still work for smaller woker node clusters (e.g. a 25 node cluster would have 24 blocks per task)
# only need to be careful for larger node clusters since you want to ensure multiple blocks per task
export N_TASKS_PER_EXECUTOR_CORE=12

export N_CORES_DRIVER=1

export LSF_PROJECT="hess"

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