#!/bin/bash

set -e

# run times with default node counts
# pass01:  7 min, pass02:  5 min, pass03: 14 min, pass04: 19 min, pass05: 24 min, pass06:  23 min
# pass07: 23 min, pass08: 11 min, pass09: 11 min, pass10: 27 min, pass11: 39 min, pass12: 128 min

if (( $# < 1 )); then
  echo "USAGE $0 <pass (1-12)> [number of nodes (overrides default)]"
  exit 1
fi

PASS="${1}"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "${TAB}"

PADDED_PASS=$(printf "%02d" "${PASS}")
PADDED_PRIOR_PASS=$(printf "%02d" "$(( PASS - 1 ))")

N5_GROUP_INPUT="/surface_align/pass${PADDED_PRIOR_PASS}"
N5_GROUP_OUTPUT="/surface_align/pass${PADDED_PASS}"

if [[ -z ${SKIP_PRIOR_PASS_DIRECTORY_CHECK} ]]; then
  validateDirectoriesExist "${N5_SAMPLE_PATH}${N5_GROUP_INPUT}"
fi

if [[ -d "${N5_SAMPLE_PATH}${N5_GROUP_OUTPUT}" ]]; then
  echo "ERROR: ${N5_SAMPLE_PATH}${N5_GROUP_OUTPUT} exists"
  exit 1
fi

# setup pass specific run class
case "${PASS}" in
  1)            N_NODES=${2:-10}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage" ;;
  2|3|4|5|6|7)  N_NODES=${2:-20}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage" ;;
  8|9|10|11|12) N_NODES=${2:-20}; CLASS="org.janelia.saalfeldlab.hotknife.SparkPairAlignFlow" ;;
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