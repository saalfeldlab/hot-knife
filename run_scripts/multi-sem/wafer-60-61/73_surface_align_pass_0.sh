#!/bin/bash

set -e

if (( $# != 3 )); then
  echo "
Usage:    $0 <max-executors> <wafer> <region>

          max-executors must be at least 2

Examples:
  $0  2  w61  r00

Notes:
  - with  2 max-executors and  5 w61 r00 slabs (79 to  83), pass 0 took  4 minutes to complete
  - with 10 max-executors and 90 w61 r00 slabs (70 to 159), pass 0 took  7 minutes to complete
"
  exit 1
fi

MAX_EXECUTORS="${1}"
if (( MAX_EXECUTORS < 2 )); then
  echo "ERROR: max-executors must be at least 2"
  exit 1
elif (( MAX_EXECUTORS > 500 )); then
  echo "ERROR: max-executors must be at most 500"
  exit 1
fi

WAFER="${2}"
REGION="${3}"

N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"
N5_SURFACE_ROOT="surface-align/run_20260303_130000"
N5_GROUP_OUTPUT="${N5_SURFACE_ROOT}/pass00"

# need scaleIndex=5 for larger wafer 53 slabs
ARGV="
--n5Path=${N5_PATH} \
--n5GroupOutput=${N5_GROUP_OUTPUT} \
--scaleIndex=7 \
--iterations 100000 \
--maxError 320 \
--maxRetries=8 \
--retryDelayMs=5000 \
--retryBackoff 2.0 \
--filter RANSAC"

RUN_TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
unset BATCH_NAME

# TODO: make serial number range a parameter or argument, note that face dataset order is important
FIRST_SERIAL_NUM=70
LAST_SERIAL_NUM=159

for SERIAL_NUM in $(seq "${FIRST_SERIAL_NUM}" "${LAST_SERIAL_NUM}"); do

  SERIAL_NUM_PADDED=$(printf "%03d" "${SERIAL_NUM}")
  RAW_STACK="${WAFER}_s${SERIAL_NUM_PADDED}_${REGION}"

  # convert w61_s079_r00 to w61_serial_070_to_079
  RENDER_PROJECT=$(awk -F'[_s]' '{w=$1; s=$3+0; lo=int(s/10)*10; hi=lo+9; printf "%s_serial_%03d_to_%03d", w, lo, hi}' <<<"${RAW_STACK}")

  # /flat_v2_mb/w61_serial_070_to_079/w61_s079_r00
  FLAT_DATASET="/flat_v3/${RENDER_PROJECT}/${RAW_STACK}"

  ARGV="${ARGV} -d ${FLAT_DATASET}/top/face -d ${FLAT_DATASET}/bot/face"

  if [[ -z "${BATCH_NAME}" ]]; then
    LAST_SERIAL=$(printf "s%03d" "${LAST_SERIAL_NUM}")
    BATCH_NAME=$(echo "surface-pass-00-${RUN_TIMESTAMP}-${RAW_STACK}-to-${LAST_SERIAL}" | sed "s/_/-/g")
  fi

done

# TODO: check that face datasets exist?

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

CLASS="org.janelia.saalfeldlab.hotknife.SparkAlignAffineGlobal"
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
