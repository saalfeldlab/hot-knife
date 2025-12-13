#!/bin/bash

set -e

if (( $# != 2 )); then
  echo "
Usage:    $0 <max-executors> <raw-stack>

          max-executors must be at least 2

Examples:
  $0  100  w61_s079_r00

Notes:
  - with 100 max-executors (and blockFactorXY 4), w61_s079_r00 took 79 minutes to complete
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

RAW_STACK="${2}"

# convert w61_s079_r00 to w61_serial_070_to_079
RENDER_PROJECT=$(awk -F'[_s]' '{w=$1; s=$3+0; lo=int(s/10)*10; hi=lo+9; printf "%s_serial_%03d_to_%03d", w, lo, hi}' <<<"${RAW_STACK}")

N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"
PROJECT_AND_NORM_LAYER_STACK="${RENDER_PROJECT}/${RAW_STACK}_gc_par_align_ic2d___norm-layer"

N5_DATASET="/render/${PROJECT_AND_NORM_LAYER_STACK}/s0"                 #          /render/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s0
N5_FIELD_MAX="/heightfields_v3/${PROJECT_AND_NORM_LAYER_STACK}/s1/max"  # /heightfields_v3/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/max

for DATASET in "${N5_DATASET}" "${N5_FIELD_MAX}"; do
  GS_PATH="${N5_PATH}${DATASET}"
  if ! gcloud storage ls "${GS_PATH}" 2>/dev/null | grep -q .; then
    echo "ERROR: ${GS_PATH} does not exist"
    exit 1
  fi
done

CLAHE_DATASET="/render/${PROJECT_AND_NORM_LAYER_STACK}_clahe/s0"

if gcloud storage ls "${N5_PATH}${CLAHE_DATASET}" 2>/dev/null | grep -q .; then
  echo "ERROR: ${N5_PATH}${CLAHE_DATASET} already exists"
  exit 1
fi

# using blockFactorXY 4 instead of default 8 to avoid OOM with larger 1024,1024,maxZ blocks
ARGV="\
--n5PathInput=${N5_PATH} \
--n5DatasetInput=${N5_DATASET} \
--n5DatasetOutput=${CLAHE_DATASET} \
--n5FieldMax=${N5_FIELD_MAX} \
--blockFactorXY 4 \
--blockFactorZ 1"

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

CLASS="org.janelia.saalfeldlab.hotknife.SparkMaskedCLAHEMultiSEM"
GS_JAR_URL="gs://janelia-spark-test/library/hot-knife-0.0.7-SNAPSHOT.jar"
BATCH_NAME=$(echo "clahe-${RUN_TIMESTAMP}-${RAW_STACK}" | sed "s/_/-/g")

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
