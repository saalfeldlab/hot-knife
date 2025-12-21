#!/bin/bash

set -e

if (( $# < 1 )); then
  echo "
Usage:    $0 <pass (1-12)> [max-executors (overrides default)]

Notes:
  - with 10 max-executors and 5 w61 r00 slabs (79 to 83), pass 1 took ?? minutes to complete
"
  exit 1
fi

PASS="${1}"

PADDED_PASS=$(printf "%02d" "${PASS}")
PADDED_PRIOR_PASS=$(printf "%02d" "$(( PASS - 1 ))")

N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"
N5_SURFACE_ROOT="surface-align/run_20251219_110000"

N5_GROUP_INPUT="${N5_SURFACE_ROOT}/pass${PADDED_PRIOR_PASS}"
N5_GROUP_OUTPUT="${N5_SURFACE_ROOT}/pass${PADDED_PASS}"

PRIOR_PASS_PATH="${N5_PATH}/${N5_GROUP_INPUT}"
if ! gcloud storage ls "${PRIOR_PASS_PATH}" 2>/dev/null | grep -q .; then
  echo "
ERROR: ${PRIOR_PASS_PATH} not found
"
  exit 1
fi

CURRENT_PASS_PATH="${N5_PATH}/${N5_GROUP_OUTPUT}"
if gcloud storage ls "${CURRENT_PASS_PATH}" 2>/dev/null | grep -q .; then
  echo "
ERROR: ${CURRENT_PASS_PATH} already exists
"
  exit 1
fi

# setup pass specific run class
case "${PASS}" in
  1|2|3)        MAX_EXECUTORS=${2:-10}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage" ;;
# TODO: uncomment and update default max executors for passes 4-12 as needed (don't forget to update error message below)
#  4)            MAX_EXECUTORS=${2:-150}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage" ;;
#  5)            MAX_EXECUTORS=${2:-200}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage" ;;
#  6|7)          MAX_EXECUTORS=${2:-150}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage" ;;
#  8|9|10)       MAX_EXECUTORS=${2:-150}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignFlow" ;;
#  11|12)        MAX_EXECUTORS=${2:-210}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignFlow" ;;
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
# TODO: uncomment and update pass args for passes 4-12 as needed
#  4)     PASS_ARGS="--scaleIndex=2 --stepSize=400 --lambdaFilter=0.1  --lambdaModel=0.01 --maxEpsilon=20" ;;
#  5|6|7) PASS_ARGS="--scaleIndex=2 --stepSize=512 --lambdaFilter=0.25 --lambdaModel=0.01 --maxEpsilon=20" ;;
#  8)     PASS_ARGS="--scaleIndex=5 --stepSize=256 --maxEpsilon=3 --sigma 30" ;;
#  9)     PASS_ARGS="--scaleIndex=4 --stepSize=256 --maxEpsilon=3 --sigma 30" ;;
#  10)    PASS_ARGS="--scaleIndex=3 --stepSize=256 --maxEpsilon=5 --sigma 30" ;;
#  11)    PASS_ARGS="--scaleIndex=2 --stepSize=256 --maxEpsilon=3 --sigma 30" ;;
#  12)    PASS_ARGS="--scaleIndex=1 --stepSize=256 --maxEpsilon=3 --sigma 30" ;;
esac

if (( MAX_EXECUTORS < 2 )); then
  echo "ERROR: max-executors must be at least 2"
  exit 1
elif (( MAX_EXECUTORS > 500 )); then
  echo "ERROR: max-executors must be at most 500"
  exit 1
fi

ARGV="
--n5Path=${N5_PATH} \
--n5GroupInput=${N5_GROUP_INPUT} \
--n5GroupOutput=${N5_GROUP_OUTPUT} \
--maxRetries=9 \
--retryDelayMs=5000 \
--retryBackoff=2.0 \
--initialDelayMs=60000 \
${PASS_ARGS}"

RUN_TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
BATCH_NAME=$(echo "surface-pass-${PADDED_PASS}-${RUN_TIMESTAMP}" | sed "s/_/-/g")

SPARK_EXEC_CORES=4

# For standard compute tier and spark runtime, total of spark.memory.offHeap.size,
# spark.executor.memory and spark.executor.memoryOverhead must be between 1024mb and 7424mb per core.
# Note that if not set, spark.executor.memoryOverhead defaults to 0.10 of spark.executor.memory.
SINGLE_CORE_MB=6700 # leave room for spark.executor.memoryOverhead, 6700 + 670 = 7370 < 7424
COMPUTE_TIER="standard"
DYNAMIC_ALLOCATION="spark.dynamicAllocation.enabled=true,spark.dynamicAllocation.maxExecutors=${MAX_EXECUTORS}"
DYNAMIC_ALLOCATION="${DYNAMIC_ALLOCATION},spark.dynamicAllocation.executorIdleTimeout=120"       # default is 60
DYNAMIC_ALLOCATION="${DYNAMIC_ALLOCATION},spark.dynamicAllocation.cachedExecutorIdleTimeout=240" # default is ?

SPARK_EXEC_MEMORY_MB=$(( SPARK_EXEC_CORES * SINGLE_CORE_MB ))

SPARK_PROPS="spark.dataproc.driver.compute.tier=${COMPUTE_TIER},spark.dataproc.executor.compute.tier=${COMPUTE_TIER}"
SPARK_PROPS="${SPARK_PROPS},spark.default.parallelism=240,spark.executor.instances=${MAX_EXECUTORS}"
SPARK_PROPS="${SPARK_PROPS},spark.executor.cores=${SPARK_EXEC_CORES},spark.executor.memory=${SPARK_EXEC_MEMORY_MB}mb"
SPARK_PROPS="${SPARK_PROPS},${DYNAMIC_ALLOCATION}"
#SPARK_PROPS="${SPARK_PROPS},spark.log.level.org.janelia.alignment.match=WARN"

# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/spark-runtime-1.1
# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/dataproc-serverless-versions
SPARK_VERSION="1.1"

GS_JAR_URL="gs://janelia-spark-test/library/hot-knife-0.0.7-SNAPSHOT.jar"

echo "
Running gcloud dataproc batches submit spark with:
  --region=us-east4
  --jars=${GS_JAR_URL}
  --class=${CLASS}
  --batch=${BATCH_NAME}
  --version=${SPARK_VERSION}
  --properties=${SPARK_PROPS}
  --async
  --
  ${ARGV}

"

# use --async to return immediately
# shellcheck disable=SC2086
gcloud dataproc batches submit spark \
  --region=us-east4 \
  --jars=${GS_JAR_URL} \
  --class=${CLASS} \
  --batch="${BATCH_NAME}" \
  --version=${SPARK_VERSION} \
  --properties="${SPARK_PROPS}" \
  --async \
  -- \
  ${ARGV}
