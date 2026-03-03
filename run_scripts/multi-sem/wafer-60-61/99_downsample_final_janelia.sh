#!/bin/bash

set -e

umask 0002

if (( $# < 1 )); then
  echo "
  USAGE $0 <number of nodes> [run and pass] [hard runtime minutes]

  Examples:
    50                                                (took 8 minutes for run_20251219_110000/pass03-sofima-fix2-full)
    20  run_20251219_110000/pass03-sofima-switchXY
  "
  exit 1
fi

N_NODES="${1}"
RUN_AND_PASS="${2:-run_20251219_110000/pass03-sofima-fix2-full}" # TODO: update this to run_20260303_130000 if necessary
export RUNTIME=${3:-240:59} # default is 10+ days, must export for flintstone

N5_SAMPLE_PATH="/nrs/hess/data/hess_wafers_60_61/export/hess_wafers_60_61.n5"
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

PARENT_ATTR_PATH="${N5_SAMPLE_PATH}${INPUT_DATASET_ROOT}/attributes.json"
if [[ ! -f "${PARENT_ATTR_PATH}" ]]; then

  PARENT_ATTRIBUTES='{
  "pixelResolution": { "dimensions": [ 8.0, 8.0, 8.0 ], "unit": "nm" },
  "ordering": "F",
  "scales": [
    [ 1, 1, 1 ],
    [ 2, 2, 2 ],
    [ 4, 4, 4 ],
    [ 8, 8, 8 ],
    [ 16, 16, 16 ],
    [ 32, 32, 32 ],
    [ 64, 64, 64 ],
    [ 128, 128, 128 ],
    [ 256, 256, 256 ]
  ],
  "axes": [ "x", "y", "z" ],
  "units": [ "nm", "nm", "nm" ]
}'

  echo "${PARENT_ATTRIBUTES}" > ${PARENT_ATTR_PATH}

  echo "
created ${PARENT_ATTR_PATH}

add or subtract scales if you get more or less downsample levels than s8

  ls -1d ${N5_SAMPLE_PATH}${INPUT_DATASET_ROOT}/s*
"
fi

NG_START_LINK='http://renderer.int.janelia.org:8080/ng/#!%7B%22layers%22:%5B%7B%22type%22:%22new%22%2C%22source%22:%22n5://http://renderer.int.janelia.org:8080/n5_sources/hess/hess_wafers_60_61.n5/%22%2C%22tab%22:%22source%22%2C%22name%22:%22hess_wafers_60_61.n5%22%7D%5D%2C%22selectedLayer%22:%7B%22visible%22:true%2C%22layer%22:%22hess_wafers_60_61.n5%22%7D%2C%22layout%22:%224panel-alt%22%7D'
echo "
When the job completes, view the volume in neuroglancer using this link:

${NG_START_LINK}

and add the following to the source URL:

${INPUT_DATASET_ROOT}

"