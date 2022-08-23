#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "$0")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh"

umask 0002

if (( $# < 1 )); then
  echo "USAGE $0 <number of nodes>"
  exit 1
fi

N_NODES="${1}"        # 10 11-slot workers takes less than 10 minutes

BASE_ZARR_COST_DIR="/nrs/flyem/bukharih"
N5_FIELD_PATH="/nrs/flyem/render/n5/${RENDER_OWNER}"

if [[ ! -d ${BASE_ZARR_COST_DIR} ]]; then
  echo "ERROR: ${BASE_ZARR_COST_DIR} not found"
  exit 1
fi

# ZARR_PATH="/nrs/flyem/bukharih/Sec25_trn_vld_single_head_jitter_flips_12-5-3-2-2-4_s3_s2_ft_20_cosine.zarr"
shopt -s nullglob
DIRS=("${BASE_ZARR_COST_DIR}/${RENDER_PROJECT}"_*.zarr/)
shopt -u nullglob # Turn off nullglob to make sure it doesn't interfere with anything later
DIR_COUNT=${#DIRS[@]}
if (( DIR_COUNT == 0 )); then
  echo "ERROR: no ${BASE_ZARR_COST_DIR}/${RENDER_PROJECT}_*.zarr directories found"
  exit 1
elif (( DIR_COUNT == 1 )); then
  ZARR_PATH=${DIRS[0]}
else
  PS3="Choose a source directory: "
  select ZARR_PATH in "${DIRS[@]}"; do
    break
  done
fi

# trim trailing slash
ZARR_PATH=${ZARR_PATH%/}

if [[ ! -d ${ZARR_PATH} ]]; then
  echo "ERROR: ${ZARR_PATH} not found"
  exit 1
fi

# ZARR_PATH="/nrs/flyem/bukharih/Sec25_trn_vld_single_head_jitter_flips_12-5-3-2-2-4_s3_s2_ft_20_cosine.zarr"
# shellcheck disable=SC2001
COST_NAME=$(echo "${ZARR_PATH}" | sed 's@.*/Sec.._\(.*\).zarr@\1@')

# HEIGHT_FIELDS_DATASET=/heightfields/Sec25/trn_vld_single_head_jitter_flips_12-5-3-2-2-4_s3_s2_ft_20_cosine
HEIGHT_FIELDS_DATASET="/heightfields/${RENDER_PROJECT}/${COST_NAME}"

# /nrs/flyem/render/n5/Z0720_07m_BR/heightfields/Sec25/trn_vld_single_head_jitter_flips_12-5-3-2-2-4_s3_s2_ft_20_cosine
if [[ -d ${N5_FIELD_PATH}${HEIGHT_FIELDS_DATASET} ]]; then
  echo "ERROR: ${N5_FIELD_PATH}${HEIGHT_FIELDS_DATASET} already exists!"
  exit 1
fi

# must export this for flintstone
export LSF_PROJECT="flyem"
export RUNTIME="3:59" # less than 4 hour max runtime maximizes available cluster nodes

#-----------------------------------------------------------
# Spark executor setup with 11 cores per worker ...

export N_EXECUTORS_PER_NODE=2 # 6
export N_CORES_PER_EXECUTOR=5 # 5
# To distribute work evenly, recommended number of tasks/partitions is 3 times the number of cores.
#N_TASKS_PER_EXECUTOR_CORE=3
export N_OVERHEAD_CORES_PER_WORKER=1
#N_CORES_PER_WORKER=$(( (N_EXECUTORS_PER_NODE * N_CORES_PER_EXECUTOR) + N_OVERHEAD_CORES_PER_WORKER ))
export N_CORES_DRIVER=1

#-----------------------------------------------------------
# prepend newer gson jar to spark classpaths to fix bug
# see https://hadoopsters.com/2019/05/08/how-to-override-a-spark-dependency-in-client-or-cluster-mode/
GSON_JAR="gson-2.8.6.jar"
GSON_JAR_PATH="/groups/flyem/data/trautmane/hot-knife/${GSON_JAR}"
export SUBMIT_ARGS="--conf spark.driver.extraClassPath=${GSON_JAR_PATH} --conf spark.executor.extraClassPath=${GSON_JAR_PATH}"

#-----------------------------------------------------------
LAUNCH_TIME=$(date +"%Y%m%d_%H%M%S")
JAR="/groups/flyem/data/render/lib/hot-knife-0.0.4-SNAPSHOT.jar"
CLASS=org.janelia.saalfeldlab.hotknife.SparkSurfaceFit


ARGV="\
--n5Path=${ZARR_PATH} \
--n5FieldPath=${N5_FIELD_PATH} \
--n5CostInput=/ \
--n5SurfaceOutput=${HEIGHT_FIELDS_DATASET} \
--firstScale=7 \
--lastScale=1 \
--maxDeltaZ=0.15 \
--initMaxDeltaZ=0.35 \
--minDistance=2000 \
--maxDistance=4000"

LOG_DIR="logs"
LOG_FILE="${LOG_DIR}/surface_fit.${RENDER_PROJECT}.${LAUNCH_TIME}.out"

mkdir -p ${LOG_DIR}

#export SPARK_JANELIA_ARGS="--consolidate_logs"

# use shell group to tee all output to log file
{

  echo """Running with arguments:
${ARGV}
"""
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh "${N_NODES}" "${JAR}" "${CLASS}" ${ARGV}
} 2>&1 | tee -a "${LOG_FILE}"
