#!/bin/bash

umask 0002

if (( $# != 1 )); then
  echo "USAGE: $0 <tab id> (e.g. Sec39)"
  exit 1
fi

# Note: tab customizations are listed below defaults
export TAB="$1"

# --------------------------------------------------------------------
# Default Parameters
# --------------------------------------------------------------------
export FLY="Z0720-07m"
export REGION="BR"

export FLY_REGION_TAB="${FLY}_${REGION}_${TAB}"
export STACK_DATA_DIR="/groups/flyem/data/${FLY_REGION_TAB}/InLens"
export DASK_DAT_TO_RENDER_WORKERS="32"

export RENDER_OWNER="Z0720_07m_BR"
export RENDER_PROJECT="${TAB}"
export BILL_TO="flyem"

export RENDER_HOST="10.40.3.162"
export RENDER_PORT="8080"
export SERVICE_HOST="${RENDER_HOST}:${RENDER_PORT}"
export RENDER_CLIENT_SCRIPTS="/groups/flyTEM/flyTEM/render/bin"
export RENDER_CLIENT_SCRIPT="$RENDER_CLIENT_SCRIPTS/run_ws_client.sh"
export RENDER_CLIENT_HEAP="1G"
export ACQUIRE_STACK="v1_acquire"
export LOCATION_STACK="${ACQUIRE_STACK}"
export ACQUIRE_TRIMMED_STACK="${ACQUIRE_STACK}_trimmed"
export OLD_ACQUIRE_TRIMMED_STACK="TBD"
export ALIGN_STACK="${ACQUIRE_TRIMMED_STACK}_align"
export INTENSITY_CORRECTED_STACK="${ALIGN_STACK}_ic"

export MATCH_OWNER="${RENDER_OWNER}"
export MATCH_COLLECTION="${RENDER_PROJECT}_v1"

export MONTAGE_PAIR_ARGS="--zNeighborDistance 0 --xyNeighborFactor 0.6 --excludeCornerNeighbors true --excludeSameLayerNeighbors false"
export MONTAGE_PASS_PAIR_SECONDS="6"

export CROSS_PAIR_ARGS="--zNeighborDistance 6 --xyNeighborFactor 0.1 --excludeCornerNeighbors false --excludeSameLayerNeighbors true"
export CROSS_PASS_PAIR_SECONDS="5"

# Try to allocate 10 minutes of match derivation work to each file.
export MAX_PAIRS_PER_FILE=30

export SCAPES_ROOT_DIR="/nrs/flyem/render/scapes"

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

# export SPARK_JANELIA_ARGS="--consolidate_logs"
export LSF_PROJECT="${BILL_TO}"
export RUNTIME="3:59"

export HOT_KNIFE_JAR="/groups/flyem/data/render/lib/hot-knife-0.0.4-SNAPSHOT.jar"
export FLINTSTONE="/groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh"

# --------------------------------------------------------------------
# Default N5 Parameters
# --------------------------------------------------------------------
export N5_SAMPLE_PATH="/nrs/flyem/render/n5/${RENDER_OWNER}"
export N5_HEIGHT_FIELDS_COMPUTED_DATASET="/heightfields/${RENDER_PROJECT}/pass1/s1"
export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass2_preibischs"
export N5_FLAT_DATASET_ROOT="/flat/${RENDER_PROJECT}"
export N5_FLAT_RAW_DATASET="${N5_FLAT_DATASET_ROOT}/raw/s0"

# --------------------------------------------------------------------
# Tab Customizations
# --------------------------------------------------------------------
case "${TAB}" in
  "Sec26")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec26/v2_acquire_trimmed_align_ic_global_3___20210518_215946"
    export N5_Z_CORR_OFFSET="192,-2385,1316"
  ;;
  "Sec27")
    export ACQUIRE_TRIMMED_STACK="v5_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export ALIGN_STACK="v5_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v5_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec27/v5_acquire_trimmed_align_ic_global_3___20210518_220105"
    export N5_Z_CORR_OFFSET="-330,-1695,1"
  ;;
  "Sec28")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec28/v3_acquire_trimmed_align_ic_global_3___20210518_223431"
    export N5_Z_CORR_OFFSET="-17488,-4261,1"
  ;;
  "Sec29")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec29/v3_acquire_trimmed_align_ic_global_3___20210519_074013"
    export N5_Z_CORR_OFFSET="-14395,-3719,1"
  ;;
  "Sec30")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec30/v3_acquire_trimmed_align_ic_global_3___20210519_074213"
    export N5_Z_CORR_OFFSET="-14832,-3955,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass3_preibischs"
  ;;
  "Sec31")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec31/v2_acquire_trimmed_align_ic_global_3___20210519_113433"
    export N5_Z_CORR_OFFSET="-811,-1174,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass3_preibischs"
  ;;
  "Sec32")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec32/v3_acquire_trimmed_align_5_ic_global_3___20210519_114445"
    export N5_Z_CORR_OFFSET="81,193,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass3_preibischs"
  ;;
  "Sec33")
    export ALIGN_STACK="v1_acquire_trimmed_sp1_adaptive_3"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_sp1_adaptive_3_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec33/v1_acquire_trimmed_sp1_adaptive_3_ic_global_3___20210519_111209"
    export N5_Z_CORR_OFFSET="291,-584,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass3_preibischs"
  ;;
  "Sec34")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec34/v2_acquire_trimmed_align_ic_global_3___20210519_112458"
    export N5_Z_CORR_OFFSET="-14640,-2987,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass3_preibischs"
  ;;
  "Sec35")
    export ALIGN_STACK="v1_acquire_trimmed_align_4"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_4_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec35/v1_acquire_trimmed_align_4_ic_global_3___20210519_113030"
    export N5_Z_CORR_OFFSET="-299,-2304,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass3_preibischs"
  ;;
  "Sec36")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_sp3_adaptive"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_sp3_adaptive_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec36/v2_acquire_trimmed_sp3_adaptive_ic_global_3___20210519_114111"
    export N5_Z_CORR_OFFSET="-280,-673,1"
  ;;
  "Sec37")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_sp1_adaptive"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_sp1_adaptive_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec37/v2_acquire_trimmed_sp1_adaptive_ic_global_3___20210519_114754"
    export N5_Z_CORR_OFFSET="285,-224,1"
  ;;
  "Sec38")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_sp1_adaptive"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_sp1_adaptive_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec38/v3_acquire_trimmed_sp1_adaptive_ic_global_3___20210519_114936"
    export N5_Z_CORR_OFFSET="-1000,-2857,1"
  ;;
  "Sec39")
    export ALIGN_STACK="v1_acquire_trimmed_sp1"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_sp1_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec39/v1_acquire_trimmed_sp1_ic_global_3___20210518_163431"
    export N5_Z_CORR_OFFSET="-1324,-2661,1"
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