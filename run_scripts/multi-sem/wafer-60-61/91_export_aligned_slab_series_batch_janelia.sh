#!/bin/bash

set -e

umask 0002

if (( $# < 1 )); then
  echo "USAGE $0 <number of nodes> [hard runtime minutes]"
  exit 1
fi

N_NODES="${1}"
export RUNTIME=${2:-240:59} # default is 10+ days, must export for flintstone

Z_BATCH="1:1"
TOP_PIXELS="3"
BOTTOM_PIXELS="-4"

N5_SAMPLE_PATH="/nrs/hess/data/hess_wafers_60_61/export/hess_wafers_60_61.n5"
RUN_AND_PASS="run_20251219_110000/pass03"
TRANSFORM_GROUP="/surface-align/${RUN_AND_PASS}"

FULL_TRANSFORM_GROUP_PATH="${N5_SAMPLE_PATH}${TRANSFORM_GROUP}"
if [[ ! -d "${FULL_TRANSFORM_GROUP_PATH}" ]]; then
  echo "ERROR: ${FULL_TRANSFORM_GROUP_PATH} does not exist"
  exit 1
fi

DATA_SET_OUTPUT="/slab-align/${RUN_AND_PASS}/s0"

FULL_DATA_SET_OUTPUT="${N5_SAMPLE_PATH}${DATA_SET_OUTPUT}"
if [[ -d "${FULL_DATA_SET_OUTPUT}" ]]; then
  echo "ERROR: ${FULL_DATA_SET_OUTPUT} already exists"
  exit 1
fi

#-----------------------------------------------------------
# setup for 11 cores per worker
export N_EXECUTORS_PER_NODE=5
export N_CORES_PER_EXECUTOR=2
# To distribute work evenly, recommended number of tasks/partitions is 3 times the number of cores.
#N_TASKS_PER_EXECUTOR_CORE=3     # default
#N_TASKS_PER_EXECUTOR_CORE=12    # 12 tasks per core used for wafer 52
export N_OVERHEAD_CORES_PER_WORKER=1
#N_CORES_PER_WORKER=$(( (N_EXECUTORS_PER_NODE * N_CORES_PER_EXECUTOR) + N_OVERHEAD_CORES_PER_WORKER ))

export N_CORES_DRIVER=1

# need this to avoid java.lang.NoSuchMethodError ... com.google.common.collect.ImmutableList.toImmutableList() ...
GUAVA_JAR="/groups/hess/hesslab/render/lib/guava-33.0.0-jre.jar"
GUAVA_FA_JAR="/groups/hess/hesslab/render/lib/failureaccess-1.0.2.jar"
export SUBMIT_ARGS="--conf spark.driver.extraClassPath=${GUAVA_JAR}:${GUAVA_FA_JAR} --conf spark.executor.extraClassPath=${GUAVA_JAR}:${GUAVA_FA_JAR}"

export SPARK_JANELIA_ARGS="--consolidate_logs --run_parent_dir /groups/hess/hesslab/render/spark_output/${USER}"
export LSF_PROJECT="hess"
export RUNTIME="233:59"

#-----------------------------------------------------------
RUN_TIME=$(date +"%Y%m%d_%H%M%S")

JAR="/groups/hess/hesslab/render/lib/hot-knife-0.0.7-SNAPSHOT.cloud-cost-debug.jar"
CLASS="org.janelia.saalfeldlab.hotknife.SparkExportAlignedSlabSeries"

ARGV="--n5PathInput ${N5_SAMPLE_PATH} \
--n5TransformGroup ${TRANSFORM_GROUP} \
--n5PathOutput ${N5_SAMPLE_PATH} \
--n5DatasetOutput ${DATA_SET_OUTPUT} \
--zBatch ${Z_BATCH} \
--blockSize=256,256,128"
# --normalizeContrast
# --explainPlan        # use --explainPlan option to output debug info without running export

# TODO: make serial number range a parameter or argument, note that face dataset order is important
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

LOG_DIR="logs"
LOG_FILE="${LOG_DIR}/slab-export-janelia.${RUN_TIME}.out"

mkdir -p ${LOG_DIR}

# use shell group to tee all output to log file
{

  echo "Running with arguments:
${ARGV}
"
  # shellcheck disable=SC2086
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh $N_NODES $JAR $CLASS $ARGV

} 2>&1 | tee -a "${LOG_FILE}"
