#!/bin/bash

set -e

ABSOLUTE_SCRIPT=`readlink -m $0`
SCRIPT_DIR=`dirname ${ABSOLUTE_SCRIPT}`
source ${SCRIPT_DIR}/00_config.sh

umask 0002

if (( $# < 1 )); then
  echo "USAGE $0 <number of nodes>"
  exit 1
fi

N_NODES="${1}"        # 30 11-slot workers takes 2+ hours

#PROJECT_Z_CORR_DIR="${N5_PATH}/z_corr/${RENDER_PROJECT}"
PROJECT_Z_CORR_DIR="${N5_PATH}/render/${RENDER_PROJECT}"

# /nrs/hess/render/export/hess.n5/render/wafer_52_cut_00030_to_00039/slab_045_all_align_t2_ic___20230123_162917

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
export RUNTIME="233:59"

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
CLASS="org.janelia.saalfeldlab.hotknife.SparkComputeCostMultiSem"

# /nrs/hess/render/export/hess.n5/render/wafer_52_cut_00030_to_00039/slab_045_all_align_t2_ic___20230123_162917
Z_CORR_DATASET=$(echo "${Z_CORR_PATH}" | sed 's@.*\(/render/.*\)@\1@')
COST_DATASET="$(echo "${Z_CORR_DATASET}" | sed 's@/render/@/cost_new/@')"
if [ "$FILTER_Y_OR_N" == "y" ]; then
  COST_DATASET="${COST_DATASET}_w_filter"
fi

if [[ -d ${N5_PATH}${COST_DATASET} ]]; then
  COST_DATASET="${COST_DATASET}__${RUN_TIME}"
fi

HEIGHT_FIELDS_DATASET=$(echo "${COST_DATASET}" | sed 's@/cost_new/@/heightfields/@')

ARGV="\
--inputN5Path=${N5_PATH} \
--inputN5Group=${Z_CORR_DATASET}/s0 \
--outputN5Path=${N5_PATH} \
--costN5Group=${COST_DATASET} \
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
--downsampleCostX \
--surfaceN5Output=${HEIGHT_FIELDS_DATASET} \
--surfaceFirstScale=8
--surfaceLastScale=1 \
--surfaceMaxDeltaZ=0.2 \
--surfaceInitMaxDeltaZ=0.2 \
--surfaceMinDistance=15 \
--surfaceMaxDistance=30"

# 6,6,1 params:

#--firstStepScaleNumber=1 \
#--costSteps=6,6,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--downsampleCostX \

# 2,2,1 params:

#--firstStepScaleNumber=1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--costSteps=2,2,1 \
#--downsampleCostX \

# TODO: find/select this instead of hard-coding ...
MAX_DELTA_Z="0.02"
#ARGV="${ARGV} --maskN5Group=/render/wafer_52_cut_00030_to_00039/slab_045_all_align_t2_ic___mask_20230224_162043/s0"
ARGV="${ARGV} --maskN5Group=/render/wafer_52_cut_00030_to_00039/slab_001_all_align_t2_ic___mask_20230321_112209/s0"
ARGV="${ARGV} --surfaceFirstScale=8 --surfaceLastScale=1 --surfaceMaxDeltaZ=${MAX_DELTA_Z} --surfaceInitMaxDeltaZ=0.01 --finalMaxDeltaZ 0.2"

if [ "$FILTER_Y_OR_N" == "y" ]; then
  ARGV="${ARGV} --normalizeImage"
fi

COST_DIR="${N5_PATH}${COST_DATASET}"
mkdir -p ${COST_DIR}
echo "${ARGV}" > ${COST_DIR}/args.txt

LOG_DIR="logs"
LOG_FILE="${LOG_DIR}/zcorr_to_cost.${RUN_TIME}.out"

mkdir -p ${LOG_DIR}

#export SPARK_JANELIA_ARGS="--consolidate_logs"

# use shell group to tee all output to log file
{

  echo """Running with arguments:
${ARGV}
"""
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh $N_NODES $JAR $CLASS $ARGV

  echo """Cost n5 volume is:
  -i ${N5_PATH} -d ${COST_DATASET}
"""
} 2>&1 | tee -a ${LOG_FILE}

