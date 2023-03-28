#!/bin/bash

umask 0002

if (( $# != 1 )); then
  echo "USAGE: $0 <cut and slab> (e.g. cut_036_slab_045)"
  exit 1
fi

# Note: slab customizations are listed below defaults
export CUT_AND_SLAB="${1}"

# --------------------------------------------------------------------
# Default Parameters
# --------------------------------------------------------------------
export RENDER_OWNER="hess"
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
export N5_SAMPLE_PATH="/nrs/hess/render/export/${RENDER_OWNER}.n5"
export N5_HEIGHT_FIELDS_DOWNSAMPLING_FACTORS="2,2,1"
export N5_FLAT_DATASET_ROOT="/flat/${CUT_AND_SLAB}"
export N5_FLAT_RAW_DATASET="${N5_FLAT_DATASET_ROOT}/raw/s0"

# --------------------------------------------------------------------
# Slab Customizations
# --------------------------------------------------------------------
case "${CUT_AND_SLAB}" in
  "cut_030_slab_026")
    export N5_Z_CORR_DATASET="/render/wafer_52_cut_00030_to_00039/slab_026_all_align_t2_ic___20230328_105408"
    export N5_Z_CORR_OFFSET="423,267,1050"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/wafer_52_cut_00030_to_00039/slab_026_all_align_t2_ic___20230328_105408"
  ;;
  "cut_031_slab_006")
    export N5_Z_CORR_DATASET="/render/wafer_52_cut_00030_to_00039/slab_006_all_align_t2_ic___20230328_141751"
    export N5_Z_CORR_OFFSET="435,238,1085"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/wafer_52_cut_00030_to_00039/slab_006_all_align_t2_ic___20230328_141751"
  ;;
  "cut_032_slab_013")
    export N5_Z_CORR_DATASET="/render/wafer_52_cut_00030_to_00039/slab_013_all_align_t2_ic___20230328_105504"
    export N5_Z_CORR_OFFSET="349,288,1120"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/wafer_52_cut_00030_to_00039/slab_013_all_align_t2_ic___20230328_105504"
  ;;
  "cut_033_slab_033")
    export N5_Z_CORR_DATASET="/render/wafer_52_cut_00030_to_00039/slab_033_all_align_t2_ic___20230328_105515"
    export N5_Z_CORR_OFFSET="346,393,1155"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/wafer_52_cut_00030_to_00039/slab_033_all_align_t2_ic___20230328_105515"
  ;;
  "cut_034_slab_020")
    export N5_Z_CORR_DATASET="/render/wafer_52_cut_00030_to_00039/slab_020_all_align_t2_ic___20230328_105528"
    export N5_Z_CORR_OFFSET="492,441,1190"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/wafer_52_cut_00030_to_00039/slab_020_all_align_t2_ic___20230328_105528"
  ;;
  "cut_035_slab_001")
    export N5_Z_CORR_DATASET="/render/wafer_52_cut_00030_to_00039/slab_001_all_align_t2_ic___20230321_134716"
    export N5_Z_CORR_OFFSET="142,131,1225"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/wafer_52_cut_00030_to_00039/slab_001_all_align_t2_ic___20230321_134716"
  ;;
  "cut_036_slab_045")
    export N5_Z_CORR_DATASET="/render/wafer_52_cut_00030_to_00039/slab_045_all_align_t2_ic___20230222_165908"
    export N5_Z_CORR_OFFSET="177,273,1260"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/wafer_52_cut_00030_to_00039/slab_045_all_align_t2_ic___20230222_165908__20230320_133412"
    # export N5_HEIGHT_FIELDS_DOWNSAMPLING_FACTORS="6,6,1"
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