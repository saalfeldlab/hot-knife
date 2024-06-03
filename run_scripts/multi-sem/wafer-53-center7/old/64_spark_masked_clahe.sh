#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "$0")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

if (( $# != 1 )); then
  echo "USAGE $0 <raw slab> (e.g. s070_m104)"
  exit 1
fi

RAW_SLAB="${1}"

source "${SCRIPT_DIR}"/00_config.sh "${RAW_SLAB}"

umask 0002

N_NODES="10"
export RUNTIME="233:59" # ten 11-core workers took 15 minutes for one slab

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
CLASS="org.janelia.saalfeldlab.hotknife.SparkMaskedCLAHEMultiSEM"

# /render/slab_000_to_009/s001_m239_align_no35_horiz_avgshd_ic___20240504_084349_norm-layer/s0

DATASET_INPUT="${N5_ALIGNED_SLAB_DATASET}_norm-layer/s0"
if [[ ! -d ${N5_SAMPLE_PATH}${DATASET_INPUT} ]]; then
  echo "ERROR: ${N5_SAMPLE_PATH}${DATASET_INPUT} does not exist"
  exit 1
fi

DATASET_OUTPUT="${N5_ALIGNED_SLAB_DATASET}_norm-layer-clahe-v2/s0"
if [[ -d ${N5_SAMPLE_PATH}${DATASET_OUTPUT} ]]; then
  echo "ERROR: ${N5_SAMPLE_PATH}${DATASET_OUTPUT} already exists"
  exit 1
fi

FIELD_MAX="${N5_HEIGHT_FIELDS_FIX_DATASET}/max"
if [[ ! -d ${N5_SAMPLE_PATH}${FIELD_MAX} ]]; then
  echo "ERROR: ${N5_SAMPLE_PATH}${FIELD_MAX} does not exist"
  exit 1
fi

# --blockFactorXY=32 \

ARGV="\
--n5PathInput=${N5_SAMPLE_PATH} \
--n5DatasetInput=${DATASET_INPUT} \
--n5DatasetOutput=${DATASET_OUTPUT} \
--n5FieldMax=${FIELD_MAX} \
--blockFactorXY=16 \
--blockFactorZ=1 \
--overwrite"

LOG_DIR="logs/64_masked_clahe"
LOG_FILE="${LOG_DIR}/masked_clahe.${RAW_SLAB}.${RUN_TIME}.out"
mkdir -p ${LOG_DIR}

# use shell group to tee all output to log file
{

  echo "Running with arguments:
${ARGV}
"
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh $N_NODES $HOT_KNIFE_JAR $CLASS $ARGV

  echo "clahe n5 volume is:
  -i ${N5_SAMPLE_PATH} -d ${DATASET_OUTPUT}
"
} 2>&1 | tee -a ${LOG_FILE}

