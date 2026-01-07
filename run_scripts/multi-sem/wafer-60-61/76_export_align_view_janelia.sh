#!/bin/bash

set -e

umask 0002

if (( $# < 1 )); then
  echo "USAGE $0 <pass (1-12)> [scaleIndex (default 2)]"
  exit 1
fi

PASS="${1}"
SCALE_INDEX="${2:-2}"

N_NODES="2"

N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"
N5_SURFACE_ROOT="surface-align/run_20251219_110000"

PADDED_PASS=$(printf "%02d" "${PASS}")
N5_GROUP_INPUT="${N5_SURFACE_ROOT}/pass${PADDED_PASS}"
ZARR_OUTPUT="/nrs/hess/data/hess_wafers_60_61/export/hess_wafers_60_61.zarr/${N5_SURFACE_ROOT}/zarr-export/pass${PADDED_PASS}"

if [[ -d "${ZARR_OUTPUT}" ]]; then
  echo "ERROR: ${ZARR_OUTPUT} already exists"
  exit 1
fi

# must export this for flintstone
export RUNTIME="233:59"

#-----------------------------------------------------------
# setup for 11 cores per worker
export N_EXECUTORS_PER_NODE=5
export N_CORES_PER_EXECUTOR=2
# To distribute work evenly, recommended number of tasks/partitions is 3 times the number of cores.
#N_TASKS_PER_EXECUTOR_CORE=3
export N_OVERHEAD_CORES_PER_WORKER=1
#N_CORES_PER_WORKER=$(( (N_EXECUTORS_PER_NODE * N_CORES_PER_EXECUTOR) + N_OVERHEAD_CORES_PER_WORKER ))

export N_CORES_DRIVER=1

export SPARK_JANELIA_ARGS="--consolidate_logs --run_parent_dir /groups/hess/hesslab/render/spark_output/${USER}"
export LSF_PROJECT="hess"
export RUNTIME="233:59"

#-----------------------------------------------------------
RUN_TIME=$(date +"%Y%m%d_%H%M%S")

JAR="/groups/hess/hesslab/render/lib/hot-knife-0.0.7-SNAPSHOT.jar"
CLASS="org.janelia.saalfeldlab.hotknife.SparkViewAlignment"

ARGV="
--n5Path=${N5_PATH} \
--n5Group ${N5_GROUP_INPUT} \
--zarrFolder ${ZARR_OUTPUT} \
--scaleIndex ${SCALE_INDEX}"

LOG_DIR="logs"
LOG_FILE="${LOG_DIR}/zarr-export-pass-${PADDED_PASS}.${RUN_TIME}.out"

mkdir -p ${LOG_DIR}

# use shell group to tee all output to log file
{

  echo "Running with arguments:
${ARGV}
"
  # shellcheck disable=SC2086
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh $N_NODES $JAR $CLASS $ARGV

} 2>&1 | tee -a "${LOG_FILE}"
