#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

umask 0002

if (( $# < 3 )); then
  echo """
USAGE: $0 <number of nodes> <render project> <raw stack>

Examples:
  $0 10 slab_070_to_079 s070_m104
"""
  exit 1
fi

N_NODES="${1}"           # 30 11-slot workers takes 4 minutes
RENDER_PROJECT="${2}"
RAW_STACK="${3}"

source "${SCRIPT_DIR}"/00_config.sh "${RAW_STACK}"

# appended to the cost and heightfields dataset names (e.g. cost_v3)
CH_RUN_VERSION="v3"

# values we ultimately used for wafer_53d in cost_v3:
NORMALIZE_METHOD="LAYER_INTENSITY" # other options: LOCAL_CONTRAST
SURFACE_INIT_MAX_DELTA="0.01"      # other options: 0.2
SURFACE_MAX_DELTA_Z="0.02"         # other options: 0.2

#-----------------------------------------------------------
NORMALIZED_LAYER_SUFFIX="_norm-layer"
NORMALIZED_LOCAL_SUFFIX="_norm-local"
if [[ "${NORMALIZE_METHOD}" == "LAYER_INTENSITY" ]]; then
  NORMALIZED_SUFFIX="${NORMALIZED_LAYER_SUFFIX}"
elif [[ "${NORMALIZE_METHOD}" == "LOCAL_CONTRAST" ]]; then
  NORMALIZED_SUFFIX="${NORMALIZED_LAYER_SUFFIX}${NORMALIZED_LOCAL_SUFFIX}"
else
  echo "ERROR: unknown NORMALIZE_METHOD of ${NORMALIZE_METHOD}"
  exit 1
fi

# /nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5/render/slab_000_to_009/s001_m239_align_no35_horiz_avgshd_ic___20240504_084349_norm-layer
SOURCE_PATH=$(ls -d "${N5_SAMPLE_PATH}/render/${RENDER_PROJECT}/${RAW_STACK}"*_avgshd_ic___202?????_??????"${NORMALIZED_SUFFIX}")
if [[ ! -d ${SOURCE_PATH} ]]; then
  echo "ERROR: source path ${SOURCE_PATH} not found"
  exit 1
fi

#-----------------------------------------------------------
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
RUN_TIME=$(date +"%Y%m%d_%H%M%S")
CLASS="org.janelia.saalfeldlab.hotknife.SparkSurfaceFit"

# /render/slab_000_to_009/s001_m239_align_no35_horiz_avgshd_ic___mask_20240504_144211
SOURCE_DATASET=${SOURCE_PATH/*\/render/\/render}

# /cost_new/slab_000_to_009/s001_m239_align_no35_horiz_avgshd_ic___mask_20240504_144211
COST_DATASET=${SOURCE_DATASET/\/render\//\/cost_${CH_RUN_VERSION}\/}

if [[ ! -d ${N5_SAMPLE_PATH}${COST_DATASET} ]]; then
  echo "ERROR: ${N5_SAMPLE_PATH}${COST_DATASET} is missing"
  exit 1
fi

# /heightfields/slab_000_to_009/s001_m239_align_no35_horiz_avgshd_ic___mask_20240504_144211
HEIGHT_FIELDS_DATASET=${SOURCE_DATASET/\/render\//\/heightfields_${CH_RUN_VERSION}\/}

if [[ -d ${N5_SAMPLE_PATH}${HEIGHT_FIELDS_DATASET} ]]; then
  echo "ERROR: ${N5_SAMPLE_PATH}${HEIGHT_FIELDS_DATASET} already exists"
  exit 1
fi

ARGV="\
--n5Path=${N5_SAMPLE_PATH} \
--n5FieldPath=${N5_SAMPLE_PATH} \
--n5CostInput=${COST_DATASET} \
--n5SurfaceOutput=${HEIGHT_FIELDS_DATASET} \
--firstScale=8 \
--lastScale=1 \
--maxDeltaZ=${SURFACE_MAX_DELTA_Z} \
--initMaxDeltaZ=${SURFACE_INIT_MAX_DELTA} \
--finalMaxDeltaZ=0.2 \
--minDistance=15 \
--maxDistance=48 \
--multiSem"

HF_DIR="${N5_SAMPLE_PATH}${COST_DATASET}"
mkdir -p "${HF_DIR}"
echo "${ARGV}" > "${HF_DIR}"/args.txt

LOG_DIR="logs"
LOG_FILE="${LOG_DIR}/hf.${RUN_TIME}.out"

mkdir -p ${LOG_DIR}

#export SPARK_JANELIA_ARGS="--consolidate_logs"

# use shell group to tee all output to log file
{

  echo "Running with arguments:
${ARGV}
"
  # shellcheck disable=SC2086
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh $N_NODES $HOT_KNIFE_JAR $CLASS $ARGV

  echo "Heightfields n5 volume is:
  -i ${N5_SAMPLE_PATH} -d ${HEIGHT_FIELDS_DATASET}
"
} 2>&1 | tee -a "${LOG_FILE}"
