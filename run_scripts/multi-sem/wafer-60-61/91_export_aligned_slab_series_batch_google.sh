#!/bin/bash

set -e

if (( $# < 1 )); then
  echo "USAGE $0 [max-executors (default 400)]"
  exit 1
fi

MAX_EXECUTORS="${1:-400}"

Z_BATCH="1:1"
TOP_PIXELS="3"
BOTTOM_PIXELS="-4"

N5_SAMPLE_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"
RUN_AND_PASS="run_20251219_110000/pass03"
TRANSFORM_GROUP="/surface-align/${RUN_AND_PASS}"

FULL_TRANSFORM_GROUP_PATH="${N5_SAMPLE_PATH}${TRANSFORM_GROUP}"

if ! gcloud storage ls "${FULL_TRANSFORM_GROUP_PATH}" 2>/dev/null | grep -q .; then
  echo "ERROR: ${FULL_TRANSFORM_GROUP_PATH} does not exist"
  exit 1
fi

DATA_SET_OUTPUT="/slab-align/${RUN_AND_PASS}/s0"

FULL_DATA_SET_OUTPUT="${N5_SAMPLE_PATH}${DATA_SET_OUTPUT}"
if gcloud storage ls "${FULL_DATA_SET_OUTPUT}" 2>/dev/null | grep -q .; then
  echo "ERROR: ${FULL_DATA_SET_OUTPUT} already exists"
  exit 1
fi

ARGV="--n5PathInput ${N5_SAMPLE_PATH} \
--n5TransformGroup ${TRANSFORM_GROUP} \
--n5PathOutput ${N5_SAMPLE_PATH} \
--n5DatasetOutput ${DATA_SET_OUTPUT} \
--zBatch ${Z_BATCH} \
--blockSize=256,256,128"
# --normalizeContrast
# --explainPlan        # use --explainPlan option to output debug info without running export

# TODO: make serial number range a parameter or argument
FIRST_SERIAL_NUM=79
LAST_SERIAL_NUM=83
WAFER="w61"
REGION="r00"

for SERIAL_NUM in $(seq "${FIRST_SERIAL_NUM}" "${LAST_SERIAL_NUM}"); do

  SERIAL_NUM_PADDED=$(printf "%03d" "${SERIAL_NUM}")
  RAW_STACK="${WAFER}_s${SERIAL_NUM_PADDED}_${REGION}"

  # convert w61_s079_r00 to w61_serial_070_to_079
  RENDER_PROJECT=$(awk -F'[_s]' '{w=$1; s=$3+0; lo=int(s/10)*10; hi=lo+9; printf "%s_serial_%03d_to_%03d", w, lo, hi}' <<<"${RAW_STACK}")

  # /flat/w61_serial_070_to_079/w61_s079_r00/raw
  FLAT_DATASET="/flat/${RENDER_PROJECT}/${RAW_STACK}/raw"

  FULL_FLAT_DATASET="${N5_SAMPLE_PATH}${FLAT_DATASET}"
  if [[ ! -d "${FULL_FLAT_DATASET}" ]]; then
    echo "ERROR: ${FULL_FLAT_DATASET} does not exist"
    exit 1
  fi

  ARGV="${ARGV} -i ${FLAT_DATASET} --top ${TOP_PIXELS} --bot ${BOTTOM_PIXELS}"

done

RUN_TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
BATCH_NAME="export-slab-series-${RUN_TIMESTAMP}"

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

CLASS="org.janelia.saalfeldlab.hotknife.SparkExportAlignedSlabSeries"
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
