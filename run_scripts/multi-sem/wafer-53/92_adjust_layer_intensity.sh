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

N_NODES="${1}"           # 30 11-slot workers takes 4 minutes
NORMALIZE_METHOD="LOCAL_CONTRAST"

SOURCE_DATASET="/wafer-53-align/run_20240409_135204/pass12"

#-----------------------------------------------------------
NORMALIZED_LAYER_SUFFIX="_norm-layer"
NORMALIZED_LOCAL_SUFFIX="_norm-local"
if [[ "${NORMALIZE_METHOD}" == "LAYER_INTENSITY" ]]; then
  SOURCE_SUFFIX=""
  NORMALIZED_SUFFIX="${NORMALIZED_LAYER_SUFFIX}"
elif [[ "${NORMALIZE_METHOD}" == "LOCAL_CONTRAST" ]]; then
  SOURCE_SUFFIX="${NORMALIZED_LAYER_SUFFIX}"
  NORMALIZED_SUFFIX="${NORMALIZED_LOCAL_SUFFIX}"
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
JAR="/groups/flyem/data/render/lib/hot-knife-0.0.5-SNAPSHOT.jar"
CLASS="org.janelia.saalfeldlab.hotknife.SparkNormalizeN5"

# /nrs/hess/render/export/hess.n5/render/wafer_52_cut_00030_to_00039/slab_045_all_align_t2_ic___20230123_162917

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

  echo """Running with arguments:
${ARGV}
"""
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh $N_NODES $JAR $CLASS $ARGV

  echo """normalized n5 volume is:
  -i ${N5_SAMPLE_PATH} -d ${NORMALIZED_DATASET}
"""
} 2>&1 | tee -a ${LOG_FILE}

