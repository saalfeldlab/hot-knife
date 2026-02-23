#!/bin/bash

set -e

umask 0002

if (( $# < 4 )); then
  echo """
USAGE: $0 <max-executors> <wafer> <region> <serial-num> [serial-num] ...

          max-executors must be at least 2

Examples:
  $0  40  w61  r00  70
  $0  40  w61  r00  71 72

Notes:
  - with 40 max-executors, w61 r00 70    took 24 minutes to complete

"""
  exit 1
fi

MAX_EXECUTORS="${1}"
if ! [[ ${MAX_EXECUTORS} =~ ^[0-9]+$ ]] || (( MAX_EXECUTORS < 2 || MAX_EXECUTORS > 500 )); then
  echo "ERROR: max-executors argument must be an integer between 2 and 500"
  exit 1
fi

WAFER="${2}"
REGION="${3}"
shift 3 # all remaining args should be serial numbers

#-----------------------------------------------------------
N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"

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

CLASS="org.janelia.saalfeldlab.hotknife.N5ConvertSparkV2"
GS_JAR_URL="gs://janelia-spark-test/library/hot-knife-0.0.7-SNAPSHOT.jar"

#-----------------------------------------------------------
for SERIAL_NUM in "$@"; do

  SERIAL_NUM_PADDED=$(printf "%03d" "${SERIAL_NUM}")
  RAW_STACK="${WAFER}_s${SERIAL_NUM_PADDED}_${REGION}"

  # convert w61_s079_r00 to w61_serial_070_to_079
  RENDER_PROJECT=$(awk -F'[_s]' '{w=$1; s=$3+0; lo=int(s/10)*10; hi=lo+9; printf "%s_serial_%03d_to_%03d", w, lo, hi}' <<<"${RAW_STACK}")

  IC2D_DATASET_PREFIX="/render/${RENDER_PROJECT}/${RAW_STACK}_gc_par_align_ic2d"
  SOURCE_DATASET="${IC2D_DATASET_PREFIX}___norm-layer-v2"

  SOURCE_PATH="${N5_PATH}${SOURCE_DATASET}"
  if ! gcloud storage ls "${SOURCE_PATH}" 2>/dev/null | grep -q .; then
    echo "ERROR: source path ${SOURCE_PATH} not found"
    exit 1
  fi

  BIG_BLOCK_DATASET="${SOURCE_DATASET}-bb"
  BIG_BLOCK_PATH="${N5_PATH}${BIG_BLOCK_DATASET}"
  if gcloud storage ls "${BIG_BLOCK_PATH}" 2>/dev/null | grep -q .; then
    echo "ERROR: big block dataset path ${BIG_BLOCK_PATH} already exists"
    exit 1
  fi

  ARGV="\
--inputN5Path=${N5_PATH} \
--inputDatasetPath=${SOURCE_DATASET}/s0 \
--outputDatasetPath=${BIG_BLOCK_DATASET}/s0 \
--blockSize 2048,2048,100"

  RUN_TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
  BATCH_NAME=$(echo "big-block-${RUN_TIMESTAMP}-${RAW_STACK}" | sed "s/_/-/g")

  echo "
In 10 seconds, running gcloud dataproc batches submit spark with:
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

  sleep 10

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

done
