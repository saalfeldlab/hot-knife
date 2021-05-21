#!/bin/bash

set -e

umask 0002

if (( $# < 1 )); then
  echo "USAGE $0 <number of nodes> [filter]"
  exit 1
fi

N_NODES="${1}"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "${TAB}"

PROJECT_Z_CORR_DIR="${N5_SAMPLE_PATH}/z_corr/${RENDER_PROJECT}"

# /nrs/flyem/render/n5/Z0720_07m_BR/z_corr/Sec39/v1_acquire_trimmed_sp1___20210410_220552

if [[ ! -d ${PROJECT_Z_CORR_DIR} ]]; then
  echo "ERROR: ${PROJECT_Z_CORR_DIR} not found"
  exit 1
fi
shopt -s nullglob
DIRS=("${PROJECT_Z_CORR_DIR}"/*/)
shopt -u nullglob # Turn off nullglob to make sure it doesn't interfere with anything later
DIR_COUNT=${#DIRS[@]}
if (( DIR_COUNT == 0 )); then
  echo "ERROR: no directories found in ${PROJECT_Z_CORR_DIR}"
  exit 1
elif (( DIR_COUNT == 1 )); then
  Z_CORR_PATH=${DIRS[0]}
else
  PS3="Choose a source directory: "
  select Z_CORR_PATH in "${DIRS[@]}"; do
    break
  done
fi

# trim trailing slash
Z_CORR_PATH="${Z_CORR_PATH%/}"

if [[ ! -d ${Z_CORR_PATH} ]]; then
  echo "ERROR: ${Z_CORR_PATH} not found"
  exit 1
fi

#-----------------------------------------------------------
CLASS="org.janelia.saalfeldlab.hotknife.SparkComputeCost"

# /nrs/flyem/render/n5/Z0720_07m_BR/z_corr/Sec39/v1_acquire_trimmed_sp1___20210410_220552
Z_CORR_DATASET="${Z_CORR_PATH/*\/z_corr/\/z_corr}"
COST_DATASET="${Z_CORR_PATH/*\/z_corr/\/cost_new}_gauss"
if (( $# > 1 )); then
  COST_DATASET="${COST_DATASET}_w_filter"
fi

if [[ -d ${N5_SAMPLE_PATH}${COST_DATASET} ]]; then
  COST_DATASET="${COST_DATASET}__${RUN_TIMESTAMP}"
fi

HEIGHT_FIELDS_DATASET="${COST_DATASET/cost_new/heightfields}"

ARGV="\
--inputN5Path=${N5_SAMPLE_PATH} \
--inputN5Group=${Z_CORR_DATASET}/s0 \
--outputN5Path=${N5_SAMPLE_PATH} \
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
--slopeCorrXRange=20 \
--downsampleCostX \
--surfaceN5Output=${HEIGHT_FIELDS_DATASET} \
--surfaceFirstScale=8
--surfaceLastScale=1 \
--surfaceMaxDeltaZ=0.25 \
--surfaceInitMaxDeltaZ=0.3 \
--surfaceMinDistance=2000 \
--surfaceMaxDistance=4000"

if (( $# > 1 )); then
  ARGV="${ARGV} --normalizeImage"
fi

COST_DIR="${N5_SAMPLE_PATH}${COST_DATASET}"
mkdir -p "${COST_DIR}"
echo "${ARGV}" > "${COST_DIR}"/args.txt

LOG_FILE=$(setupRunLog "zcorr-to-cost")

#export SPARK_JANELIA_ARGS="--consolidate_logs"

# use shell group to tee all output to log file
{

  echo "
Running with arguments:
${ARGV}
"
  # shellcheck disable=SC2086
  ${FLINTSTONE} ${N_NODES} "${HOT_KNIFE_JAR}" ${CLASS} ${ARGV}

  echo "Cost n5 volume is:
  -i ${N5_SAMPLE_PATH} -d ${COST_DATASET}
"
} 2>&1 | tee -a "${LOG_FILE}"
