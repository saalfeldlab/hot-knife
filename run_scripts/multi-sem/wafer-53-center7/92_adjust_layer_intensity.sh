#!/bin/bash

set -e

ABSOLUTE_SCRIPT=`readlink -m $0`
SCRIPT_DIR=`dirname ${ABSOLUTE_SCRIPT}`
source ${SCRIPT_DIR}/00_config.sh

umask 0002

if (( $# < 1 )); then
  echo """
USAGE: $0 <number of nodes>
"""
  exit 1
fi

N_NODES="${1}" # normalizing a 10-slab 19 mFOV volume with 180 11-slot workers took 2 hours and 15 minutes
NORMALIZE_METHOD="LOCAL_CONTRAST" # CLAHE

# TODO: change this to the correct path
SOURCE_DATASET="/wafer-53-align/run_2024MMdd_hhmmss/passNN"

#-----------------------------------------------------------
if [[ "${NORMALIZE_METHOD}" == "LOCAL_CONTRAST" ]]; then
  NORMALIZED_SUFFIX="_norm-local"
elif [[ "${NORMALIZE_METHOD}" == "CLAHE" ]]; then
  NORMALIZED_SUFFIX="_norm-clahe"
else
  echo "ERROR: unknown NORMALIZE_METHOD of ${NORMALIZE_METHOD}"
  exit 1
fi

NORMALIZED_SUFFIX="${NORMALIZED_SUFFIX}_inverted"

SOURCE_PATH="${N5_SAMPLE_PATH}${SOURCE_DATASET}"

if [[ ! -d ${SOURCE_PATH} ]]; then
  echo "ERROR: ${SOURCE_PATH} not found"
  exit 1
fi

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
CLASS="org.janelia.saalfeldlab.hotknife.SparkPixelNormalizeN5"

ARGV="\
--n5PathInput=${N5_SAMPLE_PATH} \
--n5DatasetInput=${SOURCE_DATASET} \
--normalizeMethod=${NORMALIZE_METHOD} \
--factors=2,2,1 \
--invert"

NORMALIZED_DATASET="${SOURCE_DATASET}${NORMALIZED_SUFFIX}"
NORMALIZED_DATASET_DIR="${N5_SAMPLE_PATH}${NORMALIZED_DATASET}"

if [[ ! -d ${NORMALIZED_DATASET_DIR} ]]; then
  mkdir -p "${NORMALIZED_DATASET_DIR}"
  if [[ -f ${SOURCE_PATH}/attributes.json ]]; then
    cp "${SOURCE_PATH}"/attributes.json "${NORMALIZED_DATASET_DIR}"
    echo "copied ${SOURCE_PATH}/attributes.json to ${N5_SAMPLE_PATH}${NORMALIZED_DATASET}"
  fi
fi

LOG_DIR="logs"
LOG_FILE="${LOG_DIR}/norm.${RUN_TIME}.out"

mkdir -p ${LOG_DIR}

# use shell group to tee all output to log file
{

  echo "Running with arguments:
${ARGV}
"
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh $N_NODES $HOT_KNIFE_JAR $CLASS $ARGV

  echo "normalized n5 volume is:
  -i ${N5_SAMPLE_PATH} -d ${NORMALIZED_DATASET}
"
} 2>&1 | tee -a ${LOG_FILE}

