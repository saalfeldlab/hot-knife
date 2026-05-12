#!/bin/bash

set -e

umask 0002

if (( $# < 3 )); then
  echo """
USAGE: $0 <number of executors> <render project> <raw stack>

Examples:
  $0 40 w61_serial_070_to_079 w61_s079_r00

"""
  exit 1
fi

EXECUTORS="${1}"
if ! [[ ${EXECUTORS} =~ ^[0-9]+$ ]] || (( EXECUTORS < 2 || EXECUTORS > 500 )); then
  echo "ERROR: executors argument must be an integer between 2 and 500"
  exit 1
fi

RENDER_PROJECT="${2}"
RAW_STACK="${3}"

#-----------------------------------------------------------
CLASS="org.janelia.saalfeldlab.hotknife.MultiSemNormalizeLayerIntensityHistogram"

N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"
IC2D_STACK="${RAW_STACK}_gc_par_crc_align_ic2d"
IC2D_DATASET_PREFIX="/render/${RENDER_PROJECT}/${IC2D_STACK}"
SOURCE_DATASET="${IC2D_DATASET_PREFIX}___norm-layer"

SOURCE_PATH="${N5_PATH}${SOURCE_DATASET}"
if ! gcloud storage ls "${SOURCE_PATH}" 2>/dev/null | grep -q .; then
  echo "ERROR: source path ${SOURCE_PATH} not found"
  exit 1
fi

# /heightfields_b250_smd_p1_p1/w61_serial_080_to_089/w61_s081_r00_gc_par_crc_align_ic2d___norm-layer
HF_DATASET="/heightfields_b250_smd_p1_p1/${RENDER_PROJECT}/${IC2D_STACK}___norm-layer"
HF_PATH="${N5_PATH}${HF_DATASET}"
if ! gcloud storage ls "${HF_PATH}" 2>/dev/null | grep -q .; then
  echo "ERROR: heightfield path ${HF_PATH} not found"
  exit 1
fi

HISTOGRAM_DATASET="${SOURCE_DATASET}_hist"
HISTOGRAM_DATASET_PATH="${N5_PATH}${HISTOGRAM_DATASET}"
if gcloud storage ls "${HISTOGRAM_DATASET_PATH}" 2>/dev/null | grep -q .; then
  echo "ERROR: histogram dataset path ${HISTOGRAM_DATASET_PATH} already exists"
  exit 1
fi

ARGV="\
--n5Path=${N5_PATH} \
--n5DatasetInput=${SOURCE_DATASET}/s0 \
--n5DatasetOutput=${HISTOGRAM_DATASET}/s0 \
--heightfieldDataset=${HF_DATASET}/s1/max \
--refIndex 5"

SPARK_EXEC_CORES=4

# For standard compute tier and spark runtime, total of spark.memory.offHeap.size,
# spark.executor.memory and spark.executor.memoryOverhead must be between 1024mb and 7424mb per core.
# Note that if not set, spark.executor.memoryOverhead defaults to 0.10 of spark.executor.memory.
SINGLE_CORE_MB=6700 # leave room for spark.executor.memoryOverhead, 6700 + 670 = 7370 < 7424
COMPUTE_TIER="standard"
DYNAMIC_ALLOCATION="spark.dynamicAllocation.enabled=false"

SPARK_EXEC_MEMORY_MB=$(( SPARK_EXEC_CORES * SINGLE_CORE_MB ))

SPARK_PROPS="spark.dataproc.driver.compute.tier=${COMPUTE_TIER},spark.dataproc.executor.compute.tier=${COMPUTE_TIER}"
SPARK_PROPS="${SPARK_PROPS},spark.default.parallelism=240,spark.executor.instances=${EXECUTORS}"
SPARK_PROPS="${SPARK_PROPS},spark.executor.cores=${SPARK_EXEC_CORES},spark.executor.memory=${SPARK_EXEC_MEMORY_MB}mb"
SPARK_PROPS="${SPARK_PROPS},${DYNAMIC_ALLOCATION}"
#SPARK_PROPS="${SPARK_PROPS},spark.log.level.org.janelia.alignment.match=WARN"

RUN_TIMESTAMP=$(date +"%Y%m%d-%H%M%S")

# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/spark-runtime-1.1
# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/dataproc-serverless-versions
SPARK_VERSION="1.1"

GS_JAR_URL="gs://janelia-spark-test/library/hot-knife-0.0.7-SNAPSHOT.jar"
# HOT_KNIFE_JAR="/groups/hess/hesslab/render/lib/hot-knife-0.0.7-SNAPSHOT.jar"
BATCH_NAME=$(echo "norm-hist-${RUN_TIMESTAMP}-${RAW_STACK}" | sed "s/_/-/g")

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
