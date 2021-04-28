#!/bin/bash

set -e

umask 0002

if (( $# < 2 )); then
  echo "USAGE $0 <zcorr n5 path> <number of nodes>"
  exit 1
fi

Z_CORR_PATH="$1"
N_NODES="${2}"        # 4, 10, 20

if [[ ! -d ${Z_CORR_PATH} ]]; then
  echo "ERROR: ${Z_CORR_PATH} not found"
  exit 1
fi

# must export this for flintstone
export LSF_PROJECT="flyem"

#-----------------------------------------------------------
RUN_TIME=`date +"%Y%m%d_%H%M%S"`
#DEPLOY_DIR="/groups/flyem/data/trautmane/hot-knife"
#JAR=${DEPLOY_DIR}/hot-knife-0.0.4-SNAPSHOT.jar
JAR="/groups/flyem/data/trautmane/hot-knife/render_scripts/cost/hot-knife-0.0.4-SNAPSHOT.jar"
CLASS=org.janelia.saalfeldlab.hotknife.SparkComputeCost

# /nrs/flyem/render/n5/Z0720_07m_BR/z_corr/Sec39/v1_acquire_trimmed_sp1___20210410_220552
OWNER=$(echo "${Z_CORR_PATH}" | sed 's@.*/n5/\(.*\)/z_corr/.*@\1@')
Z_CORR_DATASET=$(echo "${Z_CORR_PATH}" | sed 's@.*\(/z_corr/.*\)@\1@')
COST_DATASET=$(echo "${Z_CORR_DATASET}" | sed 's@/z_corr/@/cost_new/@')

N5_PATH="/nrs/flyem/render/n5/${OWNER}"

if [[ -d ${N5_PATH}${COST_DATASET} ]]; then
  COST_DATASET="${COST_DATASET}__${RUN_TIME}"
fi

ARGV="\
--inputN5Path=${N5_PATH} \
--inputN5Group=${Z_CORR_DATASET}/s0 \
--outputN5Path=${N5_PATH} \
--costN5Group=${COST_DATASET} \
--firstStepScaleNumber=1 \
--costSteps=6,1,6 \
--costSteps=3,1,3 \
--costSteps=3,1,3 \
--costSteps=3,1,3 \
--costSteps=3,1,3 \
--costSteps=1,4,1 \
--costSteps=1,4,1 \
--costSteps=1,4,1 \
--axisMode=2 \
--bandSize=50 \
--maxSlope=0.04 \
--slopeCorrBandFactor=5.5 \
--slopeCorrXRange=10 \
--startThresh=100 \
--downsampleCostX"
#--normalizeImage

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
