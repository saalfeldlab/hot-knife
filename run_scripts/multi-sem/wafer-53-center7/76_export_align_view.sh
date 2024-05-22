#!/bin/bash

set -e

if (( $# < 1 )); then
  echo "USAGE $0 <pass (1-12)> [scaleIndex (default 6)]"
  exit 1
fi

PASS="${1}"
SCALE_INDEX="${2:-6}"


ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "na"

PADDED_PASS=$(printf "%02d" "${PASS}")

if [[ ! -d "${N5_SAMPLE_PATH}${N5_SURFACE_ROOT}" ]]; then
  SURFACE_PARENT_DIR=$(dirname "${N5_SAMPLE_PATH}${N5_SURFACE_ROOT}")
  if [[ -d "${SURFACE_PARENT_DIR}" ]]; then
    LATEST_RUN_DIR=$(find "${SURFACE_PARENT_DIR}" -maxdepth 1 -type d -name "run_*" | tail -1)
    export N5_SURFACE_ROOT=${LATEST_RUN_DIR##${N5_SAMPLE_PATH}}
  fi
fi

N5_GROUP_INPUT="${N5_SURFACE_ROOT}/pass${PADDED_PASS}"
OUTPUT_DIR="${N5_SURFACE_ROOT}/tif-export/pass${PADDED_PASS}"

if [[ -d "${OUTPUT_DIR}" ]]; then
  echo "ERROR: ${OUTPUT_DIR} exists"
  exit 1
fi

# use 81 nodes (with 10 executors with 1 core each, so 810 cores to process the 804 surfaces)
N_NODES=${2:-81};
export N_EXECUTORS_PER_NODE=10
export N_CORES_PER_EXECUTOR=1
export N_TASKS_PER_EXECUTOR_CORE=1
CLASS="org.janelia.saalfeldlab.hotknife.SparkViewAlignment"

ARGV="
--n5Path=${N5_SAMPLE_PATH} \
-i ${N5_GROUP_OUTPUT} \
-o ${OUTPUT_DIR} \
--scaleIndex ${SCALE_INDEX}"

LOG_FILE=$(setupRunLog "surface-align-view${PADDED_PASS}")

# use shell group to tee all output to log file
{

  echo "
Running with arguments:
${ARGV}
"
  # shellcheck disable=SC2086
  ${FLINTSTONE} ${N_NODES} "${HOT_KNIFE_JAR}" ${CLASS} ${ARGV}

  echo "
When completed, view tiff stack in ${OUTPUT_DIR}
"

} 2>&1 | tee -a "${LOG_FILE}"
#!/bin/bash

set -e

if (( $# < 1 )); then
  echo "USAGE $0 <pass (1-12)> [scaleIndex (default 6)]"
  exit 1
fi

PASS="${1}"
SCALE_INDEX="${2:-6}"


ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "na"

PADDED_PASS=$(printf "%02d" "${PASS}")

if [[ ! -d "${N5_SAMPLE_PATH}${N5_SURFACE_ROOT}" ]]; then
  SURFACE_PARENT_DIR=$(dirname "${N5_SAMPLE_PATH}${N5_SURFACE_ROOT}")
  if [[ -d "${SURFACE_PARENT_DIR}" ]]; then
    LATEST_RUN_DIR=$(find "${SURFACE_PARENT_DIR}" -maxdepth 1 -type d -name "run_*" | tail -1)
    export N5_SURFACE_ROOT=${LATEST_RUN_DIR##${N5_SAMPLE_PATH}}
  fi
fi

N5_GROUP_INPUT="${N5_SURFACE_ROOT}/pass${PADDED_PASS}"
OUTPUT_DIR="${N5_SAMPLE_PATH}${N5_SURFACE_ROOT}/tif_export/pass${PADDED_PASS}/s${SCALE_INDEX}"

if [[ -d "${OUTPUT_DIR}" ]]; then
  echo "ERROR: ${OUTPUT_DIR} exists"
  exit 1
fi

# use 27 nodes (with 10 executors with 1 core each, so 270 cores to process the 804 surfaces -> ~3 surfaces/executor)
N_NODES=27;
export N_EXECUTORS_PER_NODE=10
export N_CORES_PER_EXECUTOR=1
CLASS="org.janelia.saalfeldlab.hotknife.SparkViewAlignment"

ARGV="
--n5Path=${N5_SAMPLE_PATH} \
-i ${N5_GROUP_INPUT} \
-o ${OUTPUT_DIR} \
--scaleIndex ${SCALE_INDEX}"

LOG_FILE=$(setupRunLog "surface-align-view${PADDED_PASS}")

# use shell group to tee all output to log file
{

  echo "
Running with arguments:
${ARGV}
"
  # shellcheck disable=SC2086
  ${FLINTSTONE} ${N_NODES} "${HOT_KNIFE_JAR}" ${CLASS} ${ARGV}

  echo "
When completed, view tiff stack in ${OUTPUT_DIR}
"

} 2>&1 | tee -a "${LOG_FILE}"
