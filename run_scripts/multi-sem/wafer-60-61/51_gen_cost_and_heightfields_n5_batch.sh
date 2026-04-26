#!/bin/bash

set -e

if (( $# < 4 )); then
  echo "
USAGE: $0 <number of executors> <wafer> <region> <serial-num> [serial-num] ...

Examples:
  $0  60  w61  r00  70
  $0   4  w61  r00  71 72

Notes:
  - Dataproc dynamic allocation is disabled so that SparkComputeCostMultiSem works
  - when running 9 concurrent jobs with 50 executors, 2 eventually failed with rateLimitExceeded errors

  - with 75 executors, w61 s101 and 102 r00 with 89 and 96 z-layers took  2 hours 54 minutes to complete
  - with 60 executors, w61 s079         r00 with        82 z-layers took  1 hour  30 minutes to complete
  - with 50 executors, w61 s093         r00 with        89 z-layers took  1 hour  21 minutes to complete
  - with  6 executors, w61 s085         r00 with        89 z-layers took  8 hours 44 minutes to complete
  - with  4 executors, w61 s079         r00 with        82 z-layers took 12 hours 40 minutes to complete
"
  exit 1
fi

NUM_EXECUTORS="${1}"
if ! [[ ${NUM_EXECUTORS} =~ ^[0-9]+$ ]] || (( NUM_EXECUTORS < 2 || NUM_EXECUTORS > 500 )); then
  echo "ERROR: executors argument must be an integer between 2 and 500"
  exit 1
fi

WAFER="${2}"
REGION="${3}"
shift 3 # all remaining args should be serial numbers

#-----------------------------------------------------------
RUN_TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
BATCH_NAME=$(echo "cost-${RUN_TIMESTAMP}-${RAW_STACK}" | sed "s/_/-/g")

N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"
ARGV="--n5PathInput=${N5_PATH}"

unset BATCH_NAME
for SERIAL_NUM in "$@"; do
  SERIAL_NUM_PADDED=$(printf "%03d" "${SERIAL_NUM}")
  RAW_STACK="${WAFER}_s${SERIAL_NUM_PADDED}_${REGION}"
  ARGV="${ARGV} --raw ${RAW_STACK}"
  if [[ -z "${BATCH_NAME}" ]]; then
    NUMBER_OF_STACK_MINUS_ONE=$(( $# - 1 ))
    BATCH_NAME=$(echo "cost-hf-${RUN_TIMESTAMP}-${RAW_STACK}-with-${NUMBER_OF_STACK_MINUS_ONE}" | sed "s/_/-/g")
  fi
done

CLASS="org.janelia.saalfeldlab.hotknife.SparkComputeCostMultiSemBatch"
GS_JAR_URL="gs://janelia-spark-test/library/hot-knife-0.0.7-SNAPSHOT.jar"

#-----------------------------------------------------------
SPARK_DRIVER_CORES=16
SPARK_EXEC_CORES=4

# For standard compute tier and spark runtime, total of spark.memory.offHeap.size,
# spark.executor.memory and spark.executor.memoryOverhead must be between 1024mb and 7424mb per core.
# Note that if not set, spark.executor.memoryOverhead defaults to 0.10 of spark.executor.memory.
SINGLE_CORE_MB=6700 # leave room for spark.executor.memoryOverhead, 6700 + 670 = 7370 < 7424
COMPUTE_TIER="standard"
DYNAMIC_ALLOCATION="spark.dynamicAllocation.enabled=false"

SPARK_DRIVER_MEMORY_MB=$(( SPARK_DRIVER_CORES * SINGLE_CORE_MB ))
SPARK_EXEC_MEMORY_MB=$(( SPARK_EXEC_CORES * SINGLE_CORE_MB ))

SPARK_PROPS="spark.dataproc.driver.compute.tier=${COMPUTE_TIER},spark.dataproc.executor.compute.tier=${COMPUTE_TIER}"
SPARK_PROPS="${SPARK_PROPS},spark.default.parallelism=240,spark.executor.instances=${NUM_EXECUTORS}"
SPARK_PROPS="${SPARK_PROPS},spark.executor.cores=${SPARK_EXEC_CORES},spark.executor.memory=${SPARK_EXEC_MEMORY_MB}m"
SPARK_PROPS="${SPARK_PROPS},spark.driver.cores=${SPARK_DRIVER_CORES},spark.driver.memory=${SPARK_DRIVER_MEMORY_MB}m"
SPARK_PROPS="${SPARK_PROPS},${DYNAMIC_ALLOCATION}"
#SPARK_PROPS="${SPARK_PROPS},spark.log.level.org.janelia.alignment.match=WARN"

# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/spark-runtime-1.1
# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/dataproc-serverless-versions
SPARK_VERSION="1.1"

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

# shellcheck disable=SC2086
# use --async to return immediately
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
