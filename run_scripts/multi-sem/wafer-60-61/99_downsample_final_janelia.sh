#!/bin/bash

set -e

umask 0002

if (( $# < 1 )); then
  echo "USAGE $0 <number of nodes> [hard runtime minutes]"
  exit 1
fi

N_NODES="${1}"
export RUNTIME=${2:-240:59} # default is 10+ days, must export for flintstone

N5_SAMPLE_PATH="/nrs/hess/data/hess_wafers_60_61/export/hess_wafers_60_61.n5"
RUN_AND_PASS="run_20251219_110000/pass03"
INPUT_DATASET_ROOT="/slab-align/${RUN_AND_PASS}"

FULL_INPUT_PATH="${N5_SAMPLE_PATH}${INPUT_DATASET_ROOT}/s0"
if [[ ! -d "${FULL_INPUT_PATH}" ]]; then
  echo "ERROR: ${FULL_INPUT_PATH} does not exist"
  exit 1
fi

OUTPUT_DATASET_PATHS="${INPUT_DATASET_ROOT}/s1"
FACTORS="2,2,2"
for scale in $(seq 2 9); do

  FULL_OUTPUT_PATH="${N5_SAMPLE_PATH}${INPUT_DATASET_ROOT}/s${scale}"
  if [[ -d "${FULL_OUTPUT_PATH}" ]]; then
    echo "ERROR: ${FULL_OUTPUT_PATH} already exists"
    exit 1
  fi

  OUTPUT_DATASET_PATHS="${OUTPUT_DATASET_PATHS} ${INPUT_DATASET_ROOT}/s${scale}"
  FACTORS="${FACTORS} 2,2,2"
done

#-----------------------------------------------------------
# setup for 11 cores per worker
export N_EXECUTORS_PER_NODE=5
export N_CORES_PER_EXECUTOR=2
# To distribute work evenly, recommended number of tasks/partitions is 3 times the number of cores.
#N_TASKS_PER_EXECUTOR_CORE=3     # default
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
CLASS="org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark"

ARGV="\
--n5Path=${N5_SAMPLE_PATH} \
--inputDatasetPath=${INPUT_DATASET_ROOT}/s0 \
--outputDatasetPath=${OUTPUT_DATASET_PATHS} \
--factors=${FACTORS}"

LOG_DIR="logs"
LOG_FILE="${LOG_DIR}/downsample-slab-align-janelia.${RUN_TIME}.out"

mkdir -p ${LOG_DIR}

# use shell group to tee all output to log file
{

  echo "Running with arguments:
${ARGV}
"
  # shellcheck disable=SC2086
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh $N_NODES $JAR $CLASS $ARGV

} 2>&1 | tee -a "${LOG_FILE}"
