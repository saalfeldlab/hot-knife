#!/bin/bash

umask 0002

if (( $# != 1 )); then
  echo "USAGE: $0 <slab> (e.g. s033_m039)"
  exit 1
fi

# Note: slab customizations are listed below defaults
export PROJECT="slab_030_to_039"
export CUT_AND_SLAB="${1}"

# --------------------------------------------------------------------
# Default Parameters
# --------------------------------------------------------------------
export RENDER_OWNER="hess_wafer_53b"
export BILL_TO="hess"

RUN_TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
export RUN_TIMESTAMP

# --------------------------------------------------------------------
# Default Spark Setup (11 cores per worker)
# --------------------------------------------------------------------
export N_EXECUTORS_PER_NODE=2
export N_CORES_PER_EXECUTOR=5
export N_OVERHEAD_CORES_PER_WORKER=1
# Note: N_CORES_PER_WORKER=$(( (N_EXECUTORS_PER_NODE * N_CORES_PER_EXECUTOR) + N_OVERHEAD_CORES_PER_WORKER ))

# To distribute work evenly, recommended number of tasks/partitions is 3 times the number of cores.
export N_TASKS_PER_EXECUTOR_CORE=3

export N_CORES_DRIVER=1

# Write spark logs to backed-up flyem filesystem rather than user home so that they are readable by others for analysis.
# NOTE: must consolidate logs when changing run parent dir
export SPARK_JANELIA_ARGS="--consolidate_logs --run_parent_dir /groups/hess/hesslab/render/spark_output/${USER}"
export LSF_PROJECT="${BILL_TO}"
export RUNTIME="3:59"

export HOT_KNIFE_JAR="/groups/flyem/data/render/lib/hot-knife-0.0.4-SNAPSHOT.jar"
export FLINTSTONE="/groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh"

# --------------------------------------------------------------------
# Default N5 Parameters
# --------------------------------------------------------------------
export N5_SAMPLE_PATH="/nrs/hess/data/hess_wafer_53/export/hess_wafer_53b.n5"
export N5_HEIGHT_FIELDS_DOWNSAMPLING_FACTORS="2,2,1"
export N5_FLAT_DATASET_ROOT="/flat/${CUT_AND_SLAB}"
export N5_FLAT_RAW_DATASET="${N5_FLAT_DATASET_ROOT}/raw/s0"
export N5_SURFACE_ROOT="/surface-align/run_${RUN_TIMESTAMP}"

# --------------------------------------------------------------------
# Slab Customizations
# --------------------------------------------------------------------
case "${CUT_AND_SLAB}" in
  "s070_m104")
    export RENDER_PROJECT="slab_070_to_079"
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s070_m104_align_big_block_ic___20240308_071650"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields/slab_070_to_079/s070_m104_align_big_block_ic___20240308_071650__20240308_112254"
  ;;
  "s071_m331")
    export RENDER_PROJECT="slab_070_to_079"
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s071_m331_align_big_block_ic___20240308_071902"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields/slab_070_to_079/?"
  ;;
  "s072_m150")
    export RENDER_PROJECT="slab_070_to_079"
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s072_m150_align_big_block_ic___20240308_071933"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields/slab_070_to_079/?"
  ;;
  "s073_m079")
    export RENDER_PROJECT="slab_070_to_079"
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s073_m079_align_big_block_ic___20240308_072004"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields/slab_070_to_079/?"
  ;;
  "s074_m265")
    export RENDER_PROJECT="slab_070_to_079"
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s074_m265_align_big_block_ic___20240308_072035"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields/slab_070_to_079/?"
  ;;
  "s075_m119")
    export RENDER_PROJECT="slab_070_to_079"
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s075_m119_align_big_block_ic___20240308_072106"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields/slab_070_to_079/?"
  ;;
esac

# --------------------------------------------------------------------
# Helper Functions
# --------------------------------------------------------------------
validateDirectoriesExist () {
  local DIR
  for DIR in "$@"; do
    if [[ ! -d ${DIR} ]]; then
      echo "ERROR: ${DIR} directory does not exist"
      exit 1
    fi
  done
}

setupRunLog () {
  PREFIX="$1"
  LOG_DIR="${2:-logs}"
  mkdir -p "${LOG_DIR}"
  echo "${LOG_DIR}/${PREFIX}.${RUN_TIMESTAMP}.log"
}