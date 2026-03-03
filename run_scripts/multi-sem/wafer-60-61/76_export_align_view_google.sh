#!/bin/bash

set -e

if (( $# < 1 )); then
  echo "USAGE $0 <pass (1-12)> [scaleIndex (default 2)]"
  exit 1
fi

PASS="${1}"
SCALE_INDEX="${2:-2}"

MAX_EXECUTORS="10"

N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"
N5_SURFACE_ROOT="surface-align/run_20260303_130000"

PADDED_PASS=$(printf "%02d" "${PASS}")
N5_GROUP_INPUT="${N5_SURFACE_ROOT}/pass${PADDED_PASS}"
ZARR_OUTPUT="${N5_PATH}/${N5_SURFACE_ROOT}/zarr-export/pass${PADDED_PASS}-scale${SCALE_INDEX}"

if gcloud storage ls "${ZARR_OUTPUT}" 2>/dev/null | grep -q .; then
  echo "ERROR: ${ZARR_OUTPUT} already exists"
  exit 1
fi

ARGV="
--n5Path=${N5_PATH} \
--n5Group ${N5_GROUP_INPUT} \
--zarrFolder ${ZARR_OUTPUT} \
--scaleIndex ${SCALE_INDEX}"

RUN_TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
BATCH_NAME="zarr-export-pass-${PADDED_PASS}-${RUN_TIMESTAMP}"

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

CLASS="org.janelia.saalfeldlab.hotknife.SparkViewAlignment"
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
