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
  - with 60 executors, w61 s079 r00 took  1 hour  30 minutes to complete
  - with  4 executors, w61 s079 r00 took 12 hours 40 minutes to complete
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

BOTTOM_LAYER_COST="250"
SURFACE_INIT_MAX_DELTA="0.1"
SURFACE_MAX_DELTA_Z="0.1"

# included in the cost and heightfield dataset names (e.g. b250)
COST_VERSION="b${BOTTOM_LAYER_COST}"

# included in the heightfield dataset name (e.g. smd_p01_p01 or smd_3_5)
HF_VERSION=$(
  printf 'smd_%s_%s\n' "$SURFACE_INIT_MAX_DELTA" "$SURFACE_MAX_DELTA_Z" |
  sed -E 's/_0\.([0-9]+)/_p\1/g'
)

N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"
CLASS="org.janelia.saalfeldlab.hotknife.SparkComputeCostMultiSem"
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

#-----------------------------------------------------------
for SERIAL_NUM in "$@"; do

  SERIAL_NUM_PADDED=$(printf "%03d" "${SERIAL_NUM}")
  RAW_STACK="${WAFER}_s${SERIAL_NUM_PADDED}_${REGION}"

  # convert w61_s079_r00 to w61_serial_070_to_079
  RENDER_PROJECT=$(awk -F'[_s]' '{w=$1; s=$3+0; lo=int(s/10)*10; hi=lo+9; printf "%s_serial_%03d_to_%03d", w, lo, hi}' <<<"${RAW_STACK}")

  IC2D_PATH="${N5_PATH}/render/${RENDER_PROJECT}/${RAW_STACK}_gc_par_align_ic2d"

  SOURCE_PATH="${IC2D_PATH}___norm-layer-v2-mb"
  if ! gcloud storage ls "${SOURCE_PATH}" 2>/dev/null | grep -q .; then
    echo "ERROR: source path ${SOURCE_PATH} not found"
    exit 1
  fi

  MASK_PATH="${IC2D_PATH}___mask"
  if ! gcloud storage ls "${MASK_PATH}" 2>/dev/null | grep -q .; then
    echo "ERROR: mask path ${MASK_PATH} not found"
    exit 1
  fi

  # /render/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___mask
  MASK_N5_PARENT=${MASK_PATH/*\/render/\/render}
  MASK_N5_GROUP="${MASK_N5_PARENT}/s0"

  # /render/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer-v2-mb
  SOURCE_DATASET=${SOURCE_PATH/*\/render/\/render}

  # /cost_b250/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer-v2-mb
  COST_DATASET=${SOURCE_DATASET/\/render\//\/cost_${COST_VERSION}\/}

  # /heightfields_b250_sd_p01_p01/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer-v2-mb
  HEIGHTFIELDS_DATASET=${SOURCE_DATASET/\/render\//\/heightfields_${COST_VERSION}_${HF_VERSION}\/}

  # zero surfaceMaxDistance means use last z layer of slab - this is needed for the wafer 60 and 61 data
  ARGV="\
--inputN5Path=${N5_PATH} \
--inputN5Group=${SOURCE_DATASET}/s0 \
--outputN5Path=${N5_PATH} \
--costN5Group=${COST_DATASET} \
--maskN5Group=${MASK_N5_GROUP} \
--firstStepScaleNumber=1 \
--costSteps=2,2,1 \
--costSteps=2,2,1 \
--costSteps=2,2,1 \
--costSteps=2,2,1 \
--costSteps=2,2,1 \
--costSteps=2,2,1 \
--costSteps=2,2,1 \
--costSteps=2,2,1 \
--costSteps=2,2,1 \
--topLayerCost=105 \
--bottomLayerCost=${BOTTOM_LAYER_COST} \
--surfaceN5Output=${HEIGHTFIELDS_DATASET} \
--surfaceMinDistance=15 \
--surfaceMaxDistance=0 \
--surfaceBlockSize=1024,1024 \
--surfaceFirstScale=8 \
--surfaceLastScale=1 \
--surfaceInitMaxDeltaZ=${SURFACE_INIT_MAX_DELTA} \
--surfaceMaxDeltaZ=${SURFACE_MAX_DELTA_Z} \
--finalMaxDeltaZ=0.2 \
--median \
--smoothCost"

  echo "${ARGV}" | gcloud storage cp - "${N5_PATH}${COST_DATASET}"/args.txt

  RUN_TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
  BATCH_NAME=$(echo "cost-${RUN_TIMESTAMP}-${RAW_STACK}" | sed "s/_/-/g")

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

done