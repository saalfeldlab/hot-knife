#!/bin/bash

set -e

if (( $# < 1 )); then
  echo "
Usage:    $0 <pass (1-12)> [n-nodes (overrides default)]

Notes:
  - with 150 nodes and 5 w61 r00 slabs (79 to 83), pass 4 took 48 minutes to complete
"
  exit 1
fi

PASS="${1}"

PADDED_PASS=$(printf "%02d" "${PASS}")
PADDED_PRIOR_PASS=$(printf "%02d" "$(( PASS - 1 ))")

N5_PATH="/nrs/hess/data/hess_wafers_60_61/export/hess_wafers_60_61.n5"
N5_SURFACE_ROOT="surface-align/run_20251219_110000"

N5_GROUP_INPUT="${N5_SURFACE_ROOT}/pass${PADDED_PRIOR_PASS}"
N5_GROUP_OUTPUT="${N5_SURFACE_ROOT}/pass${PADDED_PASS}"

PRIOR_PASS_PATH="${N5_PATH}/${N5_GROUP_INPUT}"
if [ ! -d "${PRIOR_PASS_PATH}" ]; then
  echo "
ERROR: ${PRIOR_PASS_PATH} not found
"
  exit 1
fi

CURRENT_PASS_PATH="${N5_PATH}/${N5_GROUP_OUTPUT}"
if [ -d "${CURRENT_PASS_PATH}" ]; then
  echo "
ERROR: ${CURRENT_PASS_PATH} already exists
"
  exit 1
fi

# setup pass specific run class
case "${PASS}" in
  1|2|3)        N_NODES=${2:-60}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage" ;;
  4)            N_NODES=${2:-150}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage" ;;
  5)            N_NODES=${2:-200}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage" ;;
  6|7)          N_NODES=${2:-150}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage" ;;
  8|9|10)       N_NODES=${2:-150}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignFlow" ;;
  11|12)        N_NODES=${2:-210}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignFlow" ;;
  *)
    echo "ERROR: 'pass parameter ${PASS} must be between 1 and 3'"
    exit 1
  ;;
esac

# setup pass specific parameters
case "${PASS}" in
  1)     PASS_ARGS="--scaleIndex=4 --stepSize=512 --lambdaFilter=0.01 --lambdaModel=0.01 --maxEpsilon=100" ;;
  2)     PASS_ARGS="--scaleIndex=4 --stepSize=400 --lambdaFilter=0.1  --lambdaModel=0.01 --maxEpsilon=50" ;;
  3)     PASS_ARGS="--scaleIndex=3 --stepSize=512 --lambdaFilter=0.1  --lambdaModel=0.01 --maxEpsilon=40" ;;
  4)     PASS_ARGS="--scaleIndex=2 --stepSize=400 --lambdaFilter=0.1  --lambdaModel=0.01 --maxEpsilon=20" ;;
  5|6|7) PASS_ARGS="--scaleIndex=2 --stepSize=512 --lambdaFilter=0.25 --lambdaModel=0.01 --maxEpsilon=20" ;;
  8)     PASS_ARGS="--scaleIndex=5 --stepSize=256 --maxEpsilon=3 --sigma 30" ;;
  9)     PASS_ARGS="--scaleIndex=4 --stepSize=256 --maxEpsilon=3 --sigma 30" ;;
  10)    PASS_ARGS="--scaleIndex=3 --stepSize=256 --maxEpsilon=5 --sigma 30" ;;
  11)    PASS_ARGS="--scaleIndex=2 --stepSize=256 --maxEpsilon=3 --sigma 30" ;;
  12)    PASS_ARGS="--scaleIndex=1 --stepSize=256 --maxEpsilon=3 --sigma 30" ;;
esac

ARGV="
--n5Path=${N5_PATH} \
--n5GroupInput=${N5_GROUP_INPUT} \
--n5GroupOutput=${N5_GROUP_OUTPUT} \
--maxRetries=9 \
--retryDelayMs=5000 \
--retryBackoff=2.0 \
--initialDelayMs=60000 \
${PASS_ARGS}"

# --------------------------------------------------------------------
# Spark Setup (11 cores per worker)
# --------------------------------------------------------------------
export N_EXECUTORS_PER_NODE=2
export N_CORES_PER_EXECUTOR=5
export N_OVERHEAD_CORES_PER_WORKER=1
# Note: N_CORES_PER_WORKER=$(( (N_EXECUTORS_PER_NODE * N_CORES_PER_EXECUTOR) + N_OVERHEAD_CORES_PER_WORKER ))

# To distribute work evenly, recommended number of tasks/partitions is 3 times the number of cores.
export N_TASKS_PER_EXECUTOR_CORE=3

# Using a single core driver for the larger wafer 53 jobs,
# we sometimes got a driver failure: TERM_MEMLIMIT: job killed after reaching LSF memory usage limit.
# So, bumping up driver to 16 cores here to avoid that possibility.
export N_CORES_DRIVER=16

HOT_KNIFE_JAR="/groups/hess/hesslab/render/lib/hot-knife-0.0.7-SNAPSHOT.cloud-cost-debug.jar"

# need this to avoid java.lang.NoSuchMethodError ... com.google.common.collect.ImmutableList.toImmutableList() ...
GUAVA_JAR="/groups/hess/hesslab/render/lib/guava-33.0.0-jre.jar"
GUAVA_FA_JAR="/groups/hess/hesslab/render/lib/failureaccess-1.0.2.jar"
export SUBMIT_ARGS="--conf spark.driver.extraClassPath=${GUAVA_JAR}:${GUAVA_FA_JAR} --conf spark.executor.extraClassPath=${GUAVA_JAR}:${GUAVA_FA_JAR}"

export SPARK_JANELIA_ARGS="--consolidate_logs --run_parent_dir /groups/hess/hesslab/render/spark_output/${USER}"
export LSF_PROJECT="hess"
export RUNTIME="233:59"

#-----------------------------------------------------------
LOG_DIR="logs"
LOG_FILE="${LOG_DIR}/surface-align-pass${PADDED_PASS}".$(date +"%Y%m%d_%H%M%S").out

mkdir -p ${LOG_DIR}

# use shell group to tee all output to log file
{

  echo "Running with arguments:
${ARGV}
"
  # shellcheck disable=SC2086
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh $N_NODES $HOT_KNIFE_JAR $CLASS $ARGV

} 2>&1 | tee -a "${LOG_FILE}"
