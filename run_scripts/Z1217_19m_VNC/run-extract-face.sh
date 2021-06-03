#!/bin/bash

# exit immediately if any step fails
set -e

# ensure files written by process are group writable
umask 0002

ABSOLUTE_SCRIPT=`readlink -m $0`
SCRIPT_DIR=`dirname ${ABSOLUTE_SCRIPT}`

if (( $# < 3 )); then
  echo """
USAGE:   $0 <slab ID> <top|bot> <number of nodes>

EXAMPLE: $0 15 top 15
"""
  exit 1
fi

SLAB_ID="${1}"
TOP_OR_BOTTOM="${2}"
N_NODES="${3}"        # 4, 10, 20

N5_PATH="/nrs/flyem/tmp/VNC-align.n5"
N5_DATASET_INPUT="/align-v2/slab-${SLAB_ID}/raw/s0"
N5_GROUP_OUTPUT="/align-v2/slab-${SLAB_ID}/${TOP_OR_BOTTOM}"

FULL_INPUT_PATH="${N5_PATH}${N5_DATASET_INPUT}"
if [[ ! -d ${FULL_INPUT_PATH} ]]; then
  echo "ERROR: missing full input path ${FULL_INPUT_PATH}"
  exit 1
fi

if [ "${TOP_OR_BOTTOM}" == "top" ]; then
  MIN='0,0,23'
  SIZE='0,0,512'
else
  if [ "${TOP_OR_BOTTOM}" == "bot" ]; then
    MIN='0,0,-23'
    SIZE='0,0,-512'
  else
    echo "ERROR: '${TOP_OR_BOTTOM} parameter must be 'top' or 'bot'"
    exit 1
  fi
fi

BLOCK_SIZE='1024,1024'

ARGV="\
--n5Path '${N5_PATH}' \
--n5DatasetInput '${N5_DATASET_INPUT}' \
--n5GroupOutput '${N5_GROUP_OUTPUT}' \
--min '${MIN}' \
--size '${SIZE}' \
--blockSize '${BLOCK_SIZE}'"

#-----------------------------------------------------------
# These parameters need to be exported for use by flintstone script
export BILL_TO="flyem"
export RUNTIME="24:00" # set hard runtime limit to 1 day
export BASE_SPARK_LOGS_DIR="/groups/flyem/data/render/spark_output"
export SPARK_VERSION="2.3.1"

#SPARK_JAR="/groups/flyem/data/trautmane/hot-knife/hot-knife-0.0.4-SNAPSHOT.jar"
SPARK_JAR="/groups/flyem/data/trautmane/hot-knife/hot-knife-0.0.4-SNAPSHOT-surface-fitting.jar"
SPARK_CLASS="org.janelia.saalfeldlab.hotknife.SparkGenerateFaceScaleSpace"

LOCAL_LOG_DIR="${SCRIPT_DIR}/logs"
mkdir -p ${LOCAL_LOG_DIR}
LOCAL_SPARK_LAUNCH_LOG="${LOCAL_LOG_DIR}/spark_launch.log"


${SCRIPT_DIR}/render-flintstone-lsd.sh ${N_NODES} ${SPARK_JAR} ${SPARK_CLASS} ${ARGV} | tee -a ${LOCAL_SPARK_LAUNCH_LOG}

echo """
When completed, view n5 export using:
  /groups/flyem/data/render/bin/n5-view.sh -i ${N5_PATH} -d ${N5_GROUP_OUTPUT}/face
"""