#!/bin/bash

set -e

if (( $# < 4 )); then
  echo "
Usage:    $0 <max-executors> <wafer> <region> <serial-num> [serial-num] ...

          max-executors must be at least 2

Exports the max heightfield (bottom surface) of each stack as a 3D uint8 surface volume
that neuroglancer can display over the ic3d pixel export, e.g.
  ${N5_PATH:-gs://janelia-spark-test/hess_wafers_60_61_export}/heightfields_v4_surface/w61_serial_070_to_079/w61_s070_r00_gc_bc_par_cc_aso_3d_s3i/max

Examples:
  $0  10  w61  r00  70
  $0  10  w61  r00  71 72

Notes:
  - run after 51_gen_cost_and_heightfields_n5_batch.sh has finished for the stacks
  - existing surface volumes are not overwritten (remove them first to rebuild them)
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
if [[ "$WAFER" != "w60" && "$WAFER" != "w61" ]]; then
  echo "ERROR: wafer must be 'w60' or 'w61'"
  exit 1
fi

REGION="${3}"
if [[ ! "$REGION" =~ ^r[0-9]{2}$ ]]; then
  echo "ERROR: REGION must be in the form rNN (e.g. r00, r11)"
  exit 1
fi

shift 3 # all remaining args should be serial numbers

N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"

ARGV="\
--n5RootPath=${N5_PATH} \
--fieldName=max \
--blockSize=256,256,128"

RUN_TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
unset BATCH_NAME
for SERIAL_NUM in "$@"; do
  SERIAL_NUM_PADDED=$(printf "%03d" "${SERIAL_NUM}")
  RAW_STACK="${WAFER}_s${SERIAL_NUM_PADDED}_${REGION}"
  ARGV="${ARGV} --raw ${RAW_STACK}"
  if [[ -z "${BATCH_NAME}" ]]; then
    NUMBER_OF_STACK_MINUS_ONE=$(( $# - 1 ))
    BATCH_NAME=$(echo "hf-surface-${RUN_TIMESTAMP}-${RAW_STACK}-with-${NUMBER_OF_STACK_MINUS_ONE}" | sed "s/_/-/g")
  fi
done

# Note: no need to check dataset existence here since SparkExportHeightFieldSurfaceMultiSemBatch does that up front

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

# set executor disk size to avoid Insufficient DISKS_TOTAL_GB quota errors from Google
SPARK_PROPS="${SPARK_PROPS},spark.dataproc.executor.disk.size=250g"

# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/spark-runtime-1.1
# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/dataproc-serverless-versions
SPARK_VERSION="1.1"

CLASS="org.janelia.saalfeldlab.hotknife.SparkExportHeightFieldSurfaceMultiSemBatch"
GS_JAR_URL="gs://janelia-spark-test/library/hot-knife-0.0.7-SNAPSHOT.jar"

OUTPUT_DIR="/Users/trautmane/Desktop/2026-11-msem/00-runs"
STAGE="52_hf_surface"
RUN_FILE="${OUTPUT_DIR}/run.$(date '+%Y%m%d').${STAGE}.txt"

echo "
Running gcloud dataproc batches submit spark with:
  --region=us-east4
  --ttl=24h
  --jars=${GS_JAR_URL}
  --class=${CLASS}
  --batch=${BATCH_NAME}
  --version=${SPARK_VERSION}
  --properties=${SPARK_PROPS}
  --async
  --
  ${ARGV}

" | tee -a "${RUN_FILE}"

# use --async to return immediately
# shellcheck disable=SC2086
gcloud dataproc batches submit spark \
  --region=us-east4 \
  --ttl=24h \
  --jars=${GS_JAR_URL} \
  --class=${CLASS} \
  --batch="${BATCH_NAME}" \
  --version=${SPARK_VERSION} \
  --properties="${SPARK_PROPS}" \
  --async \
  -- \
  ${ARGV} | tee -a "${RUN_FILE}"
