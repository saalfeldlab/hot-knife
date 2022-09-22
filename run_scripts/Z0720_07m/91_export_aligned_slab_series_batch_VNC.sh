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
  7mBR rate is roughly 300 blocks per node per hour
  1:40 w/70 nodes takes 3.5 hours
  hard runtime of < 240 minutes allows use of GPU nodes (and theoretically burst nodes)
"""
  exit 1
fi

Z_BATCH="${1}"
N_NODES="${2}"
export RUNTIME=${3:-4320} # default is minutes in 3 days

# --------------------------------------------------------------------
# setup export parameters

N5_PATH="/nrs/flyem/render/n5/Z0720_07m_VNC"
TRANSFORM_GROUP="/surface-align-VNC/final_36-06"
DATA_SET_OUTPUT="/final-align-VNC/20220922_120102/s0"

# note: Sec37 is not included because it only exists to position Sec36 to brain
ARGV="\
--n5PathInput ${N5_PATH} \
-i /flat/Sec36/raw -t 20 -b -20 \
-i /flat/Sec35/raw -t 20 -b -20 \
-i /flat/Sec34/raw -t 20 -b -20 \
-i /flat/Sec33/raw -t 20 -b -20 \
-i /flat/Sec32/raw -t 20 -b -20 \
-i /flat/Sec31/raw -t 20 -b -20 \
-i /flat/Sec30/raw -t 20 -b -20 \
-i /flat/Sec29/raw -t 20 -b -20 \
-i /flat/Sec28/raw -t 20 -b -20 \
-i /flat/Sec27/raw -t 20 -b -20 \
-i /flat/Sec26/raw -t 20 -b -20 \
-i /flat/Sec25/raw -t 20 -b -20 \
-i /flat/Sec24/raw -t 20 -b -20 \
-i /flat/Sec23/raw -t 20 -b -20 \
-i /flat/Sec22/raw -t 20 -b -20 \
-i /flat/Sec21/raw -t 20 -b -20 \
-i /flat/Sec20/raw -t 20 -b -20 \
-i /flat/Sec19/raw -t 20 -b -20 \
-i /flat/Sec18/raw -t 20 -b -20 \
-i /flat/Sec17/raw -t 20 -b -20 \
-i /flat/Sec16/raw -t 20 -b -20 \
-i /flat/Sec15/raw -t 20 -b -20 \
-i /flat/Sec14/raw -t 20 -b -20 \
-i /flat/Sec13/raw -t 20 -b -20 \
-i /flat/Sec12/raw -t 20 -b -20 \
-i /flat/Sec11/raw -t 20 -b -20 \
-i /flat/Sec10/raw -t 20 -b -20 \
-i /flat/Sec09/raw -t 20 -b -20 \
-i /flat/Sec08/raw -t 20 -b -20 \
-i /flat/Sec07/raw -t 20 -b -20 \
-i /flat/Sec06/raw -t 20 -b -20 \
--n5TransformGroup ${TRANSFORM_GROUP} \
--n5PathOutput ${N5_PATH} \
--n5DatasetOutput ${DATA_SET_OUTPUT} \
--zBatch ${Z_BATCH} \
--blockSize 128,128,128"
# --normalizeContrast
# --explainPlan        # use --explainPlan option to output debug info without running export

COMMIT="db3d1bc4c5adfa5a03dfe44213c60a00c0ed2ec0"
HOT_KNIFE_JAR="/groups/flyem/data/render/lib/hot-knife-0.0.4-${COMMIT}-SNAPSHOT.jar"
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

export LSF_PROJECT="flyem"

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