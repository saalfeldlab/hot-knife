#!/bin/bash

set -e

ABSOLUTE_SCRIPT=`readlink -m $0`
SCRIPT_DIR=`dirname ${ABSOLUTE_SCRIPT}`
source ${SCRIPT_DIR}/00_config.sh

umask 0002

if (( $# < 1 )); then
  echo "USAGE $0 <number of nodes> [filter]"
  exit 1
fi

N_NODES="${1}"        # 30 11-slot workers takes 2+ hours

N5_PATH="/nrs/flyem/render/n5/${RENDER_OWNER}"
PROJECT_Z_CORR_DIR="${N5_PATH}/z_corr/${RENDER_PROJECT}"

# /nrs/flyem/render/n5/Z0720_07m_BR/z_corr/Sec39/v1_acquire_trimmed_sp1___20210410_220552

if [[ ! -d ${PROJECT_Z_CORR_DIR} ]]; then
  echo "ERROR: ${PROJECT_Z_CORR_DIR} not found"
  exit 1
fi
shopt -s nullglob
DIRS=(${PROJECT_Z_CORR_DIR}/*/)
shopt -u nullglob # Turn off nullglob to make sure it doesn't interfere with anything later
DIR_COUNT=${#DIRS[@]}
if (( DIR_COUNT == 0 )); then
  echo "ERROR: no directories found in ${PROJECT_Z_CORR_DIR}"
  exit 1
elif (( DIR_COUNT == 1 )); then
  Z_CORR_PATH=${DIRS[0]}
else
  PS3="Choose a source directory: "
  select Z_CORR_PATH in `echo ${DIRS[@]}`; do
    break
  done
fi

# trim trailing slash
Z_CORR_PATH=$(echo "${Z_CORR_PATH}" | sed 's@/$@@')

if [[ ! -d ${Z_CORR_PATH} ]]; then
  echo "ERROR: ${Z_CORR_PATH} not found"
  exit 1
fi

# must export this for flintstone
export LSF_PROJECT="flyem"

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
RUN_TIME=`date +"%Y%m%d_%H%M%S"`
JAR="/groups/flyem/data/render/lib/hot-knife-0.0.4-SNAPSHOT.jar"
CLASS=org.janelia.saalfeldlab.hotknife.SparkSurfaceFit

# /nrs/flyem/render/n5/Z0720_07m_BR/z_corr/Sec39/v1_acquire_trimmed_sp1___20210410_220552
Z_CORR_DATASET=$(echo "${Z_CORR_PATH}" | sed 's@.*\(/z_corr/.*\)@\1@')
COST_DATASET="$(echo "${Z_CORR_DATASET}" | sed 's@/z_corr/@/cost_new/@')_gauss"
if (( $# > 1 )); then
  COST_DATASET="${COST_DATASET}_w_filter"
fi
HEIGHT_FIELDS_DATASET=$(echo "${COST_DATASET}" | sed 's@/cost_new/@/heightfields/@')

if [[ -d ${N5_PATH}${HEIGHT_FIELDS_DATASET} ]]; then
  echo "ERROR: ${N5_PATH}${HEIGHT_FIELDS_DATASET} already exists!"
  exit 1
fi

ARGV="\
--n5Path=${N5_PATH} \
--n5FieldPath=${N5_PATH} \
--n5CostInput=${COST_DATASET} \
--n5Raw=${Z_CORR_DATASET} \
--n5SurfaceOutput=${HEIGHT_FIELDS_DATASET} \
--firstScale=8 \
--lastScale=1 \
--maxDeltaZ=0.25 \
--initMaxDeltaZ=0.3 \
--minDistance=2000 \
--maxDistance=4000"

LOG_DIR="logs"
LOG_FILE="${LOG_DIR}/surface_fit.${RENDER_PROJECT}.${RUN_TIME}.out"

mkdir -p ${LOG_DIR}

#export SPARK_JANELIA_ARGS="--consolidate_logs"

# use shell group to tee all output to log file
{

  echo """Running with arguments:
${ARGV}
"""
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh $N_NODES $JAR $CLASS $ARGV
} 2>&1 | tee -a ${LOG_FILE}
