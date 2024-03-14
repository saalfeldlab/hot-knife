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
  "s030_m016")
    export RENDER_PROJECT="slab_030_to_039"
    export N5_Z_CORR_DATASET="/render/slab_030_to_039/s030_m016_align_ic___20240209_105555"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s030_m016_align_ic___20240209_105555"
  ;;
  "s031_m105")
    export RENDER_PROJECT="slab_030_to_039"
    export N5_Z_CORR_DATASET="/render/slab_030_to_039/s031_m105_align_ic___20240209_110122"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s031_m105_align_ic___20240209_110122"
  ;;
  "s032_m133")
    export RENDER_PROJECT="slab_030_to_039"
    export N5_Z_CORR_DATASET="/render/slab_030_to_039/s032_m133_align_ic___20240209_110206"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s032_m133_align_ic___20240209_110206"
  ;;
  "s033_m039")
    export RENDER_PROJECT="slab_030_to_039"
    export N5_Z_CORR_DATASET="/render/slab_030_to_039/s033_m039_align_ic___20240209_110254"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s033_m039_align_ic___20240209_110254"
  ;;
  "s034_m081")
    export RENDER_PROJECT="slab_030_to_039"
    export N5_Z_CORR_DATASET="/render/slab_030_to_039/s034_m081_align_ic___20240209_110424"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s034_m081_align_ic___20240209_110424"
  ;;
  "s035_m387")
    export RENDER_PROJECT="slab_030_to_039"
    export N5_Z_CORR_DATASET="/render/slab_030_to_039/s035_m387_align_ic___20240209_110445"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s035_m387_align_ic___20240209_110445"
  ;;
  "s036_m252")
    export RENDER_PROJECT="slab_030_to_039"
    export N5_Z_CORR_DATASET="/render/slab_030_to_039/s036_m252_align_ic___20240209_111340"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s036_m252_align_ic___20240209_111340"
  ;;
  "s037_m381")
    export RENDER_PROJECT="slab_030_to_039"
    export N5_Z_CORR_DATASET="/render/slab_030_to_039/s037_m381_align_ic___20240209_111354"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s037_m381_align_ic___20240209_111354"
  ;;
  "s038_m139")
    export RENDER_PROJECT="slab_030_to_039"
    export N5_Z_CORR_DATASET="/render/slab_030_to_039/s038_m139_align_ic___20240209_111419"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s038_m139_align_ic___20240209_111419"
  ;;
  "s039_m295")
    export RENDER_PROJECT="slab_030_to_039"
    export N5_Z_CORR_DATASET="/render/slab_030_to_039/s039_m295_align_ic___20240209_111518"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s039_m295_align_ic___20240209_111518"
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