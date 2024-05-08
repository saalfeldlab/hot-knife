#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}"/00_config.sh "NA"

umask 0002

if (( $# < 2 )); then
  echo """
USAGE: $0 <number of nodes> <raw stack> [raw stack] [raw stack] ...

Examples:
  $0 1 s070_m104
"""
  exit 1
fi

N_NODES="${1}"
shift

FROM_HF_VERSION="heightfields_v4"
TO_HF_VERSION="heightfields_fix"

LAUNCH_TIME=$(date +"%Y%m%d_%H%M%S")
HF_FIELD_GROUPS_DIR="${SCRIPT_DIR}/hf_field_groups"
mkdir -p "${HF_FIELD_GROUPS_DIR}"
FIELD_GROUPS_JSON_FILE="${HF_FIELD_GROUPS_DIR}/field_groups.${LAUNCH_TIME}.json"

# [
#   { "from": "heightfields_v4/slab_070_to_079/s071_m331_align_no35_horiz_avgshd_ic___20240504_085310_norm-layer", "to": "heightfields_fix/slab_070_to_079/s071_m331" },
#   { "from": "heightfields_v4/slab_070_to_079/s072_m150_align_no35_horiz_avgshd_ic___20240504_085313_norm-layer", "to": "heightfields_fix/slab_070_to_079/s072_m150" },
#   { "from": "heightfields_v4/slab_110_to_119/s112_m213_align_no35_horiz_avgshd_ic___20240504_085505_norm-layer", "to": "heightfields_fix/slab_110_to_119/s112_m213" }
# ]
echo "[" > "${FIELD_GROUPS_JSON_FILE}"

STACK_COUNT=0
for RAW_STACK in "$@"; do
  SLAB_PROJECT=$(getSlabProjectName "${RAW_STACK}")
  # /nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5/heightfields_v4/slab_000_to_009/s001_m239_align_no35_horiz_avgshd_ic___20240504_084349_norm-layer
  HF_PATH=$(ls -d "${N5_SAMPLE_PATH}/${FROM_HF_VERSION}/${SLAB_PROJECT}/${RAW_STACK}"*_avgshd_ic___202?????_??????_norm-layer)
  if [[ ! -d ${HF_PATH} ]]; then
    echo "ERROR: for ${RAW_STACK}, ${HF_PATH} not found"
    exit 1
  fi

  # /slab_000_to_009/s001_m239_align_no35_horiz_avgshd_ic___20240504_084349_norm-layer
  SLAB_DATASET=${HF_PATH/*\/slab_/\/slab_}

  # /heightfields_v4/slab_000_to_009/s001_m239_align_no35_horiz_avgshd_ic___20240504_084349_norm-layer
  HF_DATASET="/${FROM_HF_VERSION}${SLAB_DATASET}/s1"

  # /heightfields_fix/slab_000_to_009/s001_m239
  HF_OUT_DATASET="/${TO_HF_VERSION}/${SLAB_PROJECT}/${RAW_STACK}"

  if [[ -d ${N5_SAMPLE_PATH}${HF_OUT_DATASET} ]]; then
    echo "ERROR: ${N5_SAMPLE_PATH}${HF_OUT_DATASET} already exists"
    exit 1
  fi

  if (( STACK_COUNT > 0 )); then
      echo "," >> "${FIELD_GROUPS_JSON_FILE}"
  fi
  echo -n "{ \"from\": \"${HF_DATASET}\", \"to\": \"${HF_OUT_DATASET}\" }" >> "${FIELD_GROUPS_JSON_FILE}"

  STACK_COUNT=$(( STACK_COUNT + 1 ))
done

echo "
]" >> "${FIELD_GROUPS_JSON_FILE}"

ARGV=" \
--n5=${N5_SAMPLE_PATH} \
--scale=2,2,1 \
--dataSetJsonFile=${FIELD_GROUPS_JSON_FILE}"

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
export RUNTIME="233:59" # must export this for flintstone
CLASS="org.janelia.saalfeldlab.hotknife.SparkResaveMultiSemHeightField"

LOG_DIR="logs"
LOG_FILE="${LOG_DIR}/hf_fix.${LAUNCH_TIME}.out"

mkdir -p ${LOG_DIR}

#export SPARK_JANELIA_ARGS="--consolidate_logs"

# use shell group to tee all output to log file
{

  echo "Running with arguments:
${ARGV}
"
  # shellcheck disable=SC2086
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh $N_NODES $HOT_KNIFE_JAR $CLASS $ARGV
} 2>&1 | tee -a "${LOG_FILE}"
