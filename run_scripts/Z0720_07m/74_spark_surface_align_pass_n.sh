#!/bin/bash

set -e

# run times with 10 nodes for Z0720_07m_BR Sec06 - Sec26 (40 datasets)
# pass01: 10 min

# run times with 20 nodes for Z0720_07m_BR Sec06 - Sec26 (40 datasets)
# pass02: 21 min, pass03: 20 min, pass04: 32 min, pass05: 48 min, pass06: 34 min
# pass07: 44 min, pass08: 16 min, pass09: 18 min, pass10: 54 min, pass11: 86 min, pass12: >300 min

# next time, will run with increased node counts (key is to get pass12 run under 4 hours)

if (( $# < 3 )); then
  echo "USAGE $0 <pass (1-12)> <min tab number> <max tab number> [number of nodes (overrides default)]"
  exit 1
fi

PASS="${1}"
# needs to be exported so that 00_config picks it up for N5_SURFACE_ROOT definition
export MIN_SEC_NUM="$2"
export MAX_SEC_NUM="$3"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

# only load config if it has yet to be loaded
if [ -z ${N5_SURFACE_ROOT} ]; then
  source "${SCRIPT_DIR}/00_config.sh" "tab_not_applicable"
fi

PADDED_PASS=$(printf "%02d" "${PASS}")
PADDED_PRIOR_PASS=$(printf "%02d" "$(( PASS - 1 ))")

if [[ ! -d "${N5_SAMPLE_PATH}${N5_SURFACE_ROOT}" ]]; then
  SURFACE_PARENT_DIR=$(dirname "${N5_SAMPLE_PATH}${N5_SURFACE_ROOT}")
  if [[ -d "${SURFACE_PARENT_DIR}" ]]; then
    LATEST_RUN_DIR=$(find "${SURFACE_PARENT_DIR}" -maxdepth 1 -type d -name "run_*" | tail -1)
    export N5_SURFACE_ROOT=${LATEST_RUN_DIR##${N5_SAMPLE_PATH}}
  fi
fi

N5_GROUP_INPUT="${N5_SURFACE_ROOT}/pass${PADDED_PRIOR_PASS}"
N5_GROUP_OUTPUT="${N5_SURFACE_ROOT}/pass${PADDED_PASS}"

if [[ -z ${SKIP_PRIOR_PASS_DIRECTORY_CHECK} ]]; then
  validateDirectoriesExist "${N5_SAMPLE_PATH}${N5_GROUP_INPUT}"
fi

if [[ -d "${N5_SAMPLE_PATH}${N5_GROUP_OUTPUT}" ]]; then
  echo "ERROR: ${N5_SAMPLE_PATH}${N5_GROUP_OUTPUT} exists"
  exit 1
fi

# setup pass specific run class
case "${PASS}" in
  1)            N_NODES=${4:-20}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage" ;;
  2|3|4|5|6|7)  N_NODES=${4:-40}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage" ;;
  8|9|10|11)    N_NODES=${4:-40}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignFlow" ;;
  12)           N_NODES=${4:-80}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignFlow" ;;
  *)
    echo "ERROR: 'pass parameter ${PASS} must be between 1 and 12'"
    exit 1
  ;;
esac

# setup pass specific parameters
case "${PASS}" in
  1)     PASS_ARGS="--scaleIndex=4 --stepSize=512 --lambdaFilter=0.01 --lambdaModel=0.01 --maxEpsilon=100" ;;
  2)     PASS_ARGS="--scaleIndex=4 --stepSize=400 --lambdaFilter=0.1  --lambdaModel=0.01 --maxEpsilon=50" ;;
  3)     PASS_ARGS="--scaleIndex=3 --stepSize=512 --lambdaFilter=0.1  --lambdaModel=0.01 --maxEpsilon=40" ;;
  4)     PASS_ARGS="--scaleIndex=2 --stepSize=400 --lambdaFilter=0.1  --lambdaModel=0.01 --maxEpsilon=20" ;;
  5|6|7) PASS_ARGS="--scaleIndex=2 --stepSize=512 --lambdaFilter=0.25 --lambdaModel=0.01 --maxEpsilon=20" ;;
  8)     PASS_ARGS="--scaleIndex=5 --stepSize=256 --maxEpsilon=3 --sigma 30" ;;
  9)     PASS_ARGS="--scaleIndex=4 --stepSize=256 --maxEpsilon=3 --sigma 30" ;;
  10)    PASS_ARGS="--scaleIndex=3 --stepSize=256 --maxEpsilon=5 --sigma 30" ;;
  11)    PASS_ARGS="--scaleIndex=2 --stepSize=256 --maxEpsilon=3 --sigma 30" ;;
  12)    PASS_ARGS="--scaleIndex=1 --stepSize=256 --maxEpsilon=3 --sigma 30" ;;
esac

ARGV="
  --n5Path=${N5_SAMPLE_PATH} \
  --n5GroupInput=${N5_GROUP_INPUT} \
  --n5GroupOutput=${N5_GROUP_OUTPUT} \
  ${PASS_ARGS}"

LOG_FILE=$(setupRunLog "surface-align-pass${PADDED_PASS}")

# use shell group to tee all output to log file
{

  echo "
Running with arguments:
${ARGV}
"
  # shellcheck disable=SC2086
  ${FLINTSTONE} ${N_NODES} "${HOT_KNIFE_JAR}" ${CLASS} ${ARGV}

  echo "
When completed, view n5 using:
  n5-view.sh -i ${N5_SAMPLE_PATH} -d ${N5_GROUP_OUTPUT}
"

} 2>&1 | tee -a "${LOG_FILE}"