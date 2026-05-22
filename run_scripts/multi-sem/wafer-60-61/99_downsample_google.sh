#!/bin/bash

set -e

umask 0002

if (( $# != 2 )); then
  echo """
USAGE: $0 <max executors> <full resolution dataset>

Examples:
  $0  2  /render/w61_serial_130_to_139/w61_s131_r00_gc_par_crc_align_ic2d___norm-layer/s0

"""
  exit 1
fi

MAX_EXECUTORS="${1}"
if ! [[ ${MAX_EXECUTORS} =~ ^[0-9]+$ ]] || (( MAX_EXECUTORS < 2 || MAX_EXECUTORS > 500 )); then
  echo "ERROR: max executors argument must be an integer between 2 and 500"
  exit 1
fi

FULL_RES_DATASET="${2}"

#-----------------------------------------------------------
CLASS="org.janelia.saalfeldlab.hotknife.util.DownsampleHelper"

N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"

SOURCE_PATH="${N5_PATH}${FULL_RES_DATASET}"
if ! gcloud storage ls "${SOURCE_PATH}" 2>/dev/null | grep -q .; then
  echo "ERROR: full resolution dataset ${SOURCE_PATH} not found"
  exit 1
fi

ARGV="\
--basePathOrStorageUrl=${N5_PATH} \
--fullResolutionDataset=${FULL_RES_DATASET} \
--factors 2,2,1"

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

RUN_TIMESTAMP=$(date +"%Y%m%d-%H%M%S")

# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/spark-runtime-1.1
# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/dataproc-serverless-versions
SPARK_VERSION="1.1"

GS_JAR_URL="gs://janelia-spark-test/library/hot-knife-0.0.7-SNAPSHOT.jar"
# HOT_KNIFE_JAR="/groups/hess/hesslab/render/lib/hot-knife-0.0.7-SNAPSHOT.jar"
BATCH_NAME=$(echo "downsample-${RUN_TIMESTAMP}" | sed "s/_/-/g")

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
