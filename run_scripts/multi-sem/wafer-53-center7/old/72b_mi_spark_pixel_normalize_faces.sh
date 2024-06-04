#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "$0")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}"/00_config.sh "NA"

umask 0002

if (( $# != 1 )); then
  echo "USAGE $0 <slab and surface> (e.g. s001_m239/top4)"
  exit 1
fi

SLAB_AND_SURFACE="${1}"
N_NODES="2"
NORMALIZE_METHOD="CLAHE" # LOCAL_CONTRAST, CLAHE

export RUNTIME="59" # these run fast (4 minutes with 2 11-core workers), use < 60 minute limit to get on short queue

#-----------------------------------------------------------
if [[ "${NORMALIZE_METHOD}" == "LOCAL_CONTRAST" ]]; then
  NORMALIZED_SUFFIX="_cllcn"
elif [[ "${NORMALIZE_METHOD}" == "CLAHE" ]]; then
  NORMALIZED_SUFFIX="_clahe"
else
  echo "ERROR: unknown NORMALIZE_METHOD of ${NORMALIZE_METHOD}"
  exit 1
fi

#-----------------------------------------------------------
# Spark executor setup with 11 cores per worker ...

export N_EXECUTORS_PER_NODE=2
export N_CORES_PER_EXECUTOR=5
# To distribute work evenly, recommended number of tasks/partitions is 3 times the number of cores.
#N_TASKS_PER_EXECUTOR_CORE=3
export N_OVERHEAD_CORES_PER_WORKER=1
#N_CORES_PER_WORKER=$(( (N_EXECUTORS_PER_NODE * N_CORES_PER_EXECUTOR) + N_OVERHEAD_CORES_PER_WORKER ))
export N_CORES_DRIVER=1

#-----------------------------------------------------------
RUN_TIME=$(date +"%Y%m%d_%H%M%S")
CLASS="org.janelia.saalfeldlab.hotknife.SparkPixelNormalizeN5"

SOURCE_DATASET="/flat_mask/${SLAB_AND_SURFACE}/face"
if [[ ! -d ${N5_SAMPLE_PATH}${SOURCE_DATASET} ]]; then
  echo "ERROR: ${N5_SAMPLE_PATH}${SOURCE_DATASET} does not exist"
  exit 1
fi

NORMALIZED_DATASET="/flat_mask/${SLAB_AND_SURFACE}${NORMALIZED_SUFFIX}/face"
if [[ -d ${N5_SAMPLE_PATH}${NORMALIZED_DATASET} ]]; then
  echo "ERROR: ${N5_SAMPLE_PATH}${NORMALIZED_DATASET} already exists"
  exit 1
fi

ARGV="\
--n5PathInput=${N5_SAMPLE_PATH} \
--n5DatasetInput=${SOURCE_DATASET} \
--n5DatasetOutput=${NORMALIZED_DATASET} \
--normalizeMethod=${NORMALIZE_METHOD} \
--scaleIndexList=0,1,2,3,4,5,6,7,8,9 \
--blockFactorXY 2 \
--invert"

LOG_DIR="logs/72b_mi_normalize_faces"
SLAB_AND_SURFACE_NAME="${SLAB_AND_SURFACE//\//_}"
LOG_FILE="${LOG_DIR}/norm.${SLAB_AND_SURFACE_NAME}.${RUN_TIME}.out"
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

