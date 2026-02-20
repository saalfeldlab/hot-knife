#!/bin/bash

set -e

umask 0002

if (( $# < 3 )); then
  echo """
USAGE: $0 <number of nodes> <render project> <raw stack>

Examples:
  $0 40 w61_serial_070_to_079 w61_s079_r00

"""
  exit 1
fi

N_NODES="${1}" # 2 11-slot nodes took 228 minutes for w60_s360_r00_d20_gc_align_small_block
RENDER_PROJECT="${2}"
RAW_STACK="${3}"

#-----------------------------------------------------------
CLASS="org.janelia.saalfeldlab.hotknife.SparkNormalizeLayerIntensityN5"

N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"
IC2D_DATASET_PREFIX="/render/${RENDER_PROJECT}/${RAW_STACK}_gc_par_align_ic2d"
SOURCE_DATASET="${IC2D_DATASET_PREFIX}___pixel"

SOURCE_PATH="${N5_PATH}${SOURCE_DATASET}"
if ! gcloud storage ls "${SOURCE_PATH}" 2>/dev/null | grep -q .; then
  echo "ERROR: source path ${SOURCE_PATH} not found"
  exit 1
fi

NORMALIZED_DATASET="${IC2D_DATASET_PREFIX}___norm-layer-v2"
NORMALIZED_DATASET_PATH="${N5_PATH}${NORMALIZED_DATASET}"
if gcloud storage ls "${NORMALIZED_DATASET_PATH}" 2>/dev/null | grep -q .; then
  echo "ERROR: normalized dataset path ${NORMALIZED_DATASET_PATH} already exists"
  exit 1
fi

ARGV="\
--n5Path=${N5_PATH} \
--n5DatasetInput=${SOURCE_DATASET} \
--n5DatasetOutput=${NORMALIZED_DATASET} \
--factors 2,2,1"
# --invert"

SPARK_EXEC_CORES=4

# For standard compute tier and spark runtime, total of spark.memory.offHeap.size,
# spark.executor.memory and spark.executor.memoryOverhead must be between 1024mb and 7424mb per core.
# Note that if not set, spark.executor.memoryOverhead defaults to 0.10 of spark.executor.memory.
SINGLE_CORE_MB=6700 # leave room for spark.executor.memoryOverhead, 6700 + 670 = 7370 < 7424
COMPUTE_TIER="standard"
DYNAMIC_ALLOCATION="spark.dynamicAllocation.enabled=false"

SPARK_EXEC_MEMORY_MB=$(( SPARK_EXEC_CORES * SINGLE_CORE_MB ))

SPARK_PROPS="spark.dataproc.driver.compute.tier=${COMPUTE_TIER},spark.dataproc.executor.compute.tier=${COMPUTE_TIER}"
SPARK_PROPS="${SPARK_PROPS},spark.default.parallelism=240,spark.executor.instances=${N_NODES}"
SPARK_PROPS="${SPARK_PROPS},spark.executor.cores=${SPARK_EXEC_CORES},spark.executor.memory=${SPARK_EXEC_MEMORY_MB}mb"
SPARK_PROPS="${SPARK_PROPS},${DYNAMIC_ALLOCATION}"
#SPARK_PROPS="${SPARK_PROPS},spark.log.level.org.janelia.alignment.match=WARN"

RUN_TIMESTAMP=$(date +"%Y%m%d-%H%M%S")

# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/spark-runtime-1.1
# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/dataproc-serverless-versions
SPARK_VERSION="1.1"

GS_JAR_URL="gs://janelia-spark-test/library/hot-knife-0.0.7-SNAPSHOT.jar"
# HOT_KNIFE_JAR="/groups/hess/hesslab/render/lib/hot-knife-0.0.7-SNAPSHOT.jar"
BATCH_NAME=$(echo "norm-layer-${RUN_TIMESTAMP}-${RAW_STACK}" | sed "s/_/-/g")

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
