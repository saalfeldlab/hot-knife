#!/bin/bash

umask 0002

if (( $# != 1 )); then
  echo "USAGE: $0 <tab id> (e.g. Sec15)"
  exit 1
fi

# Note: tab customizations are listed below defaults
export TAB="$1"

# --------------------------------------------------------------------
# Default Parameters
# --------------------------------------------------------------------
export FLY="Z0720-07m"
export REGION="VNC"

export FLY_REGION_TAB="${FLY}_${REGION}_${TAB}"
export STACK_DATA_DIR="/groups/flyem/data/${FLY_REGION_TAB}/InLens"
export DASK_DAT_TO_RENDER_WORKERS="32"

export RENDER_OWNER="Z0720_07m_VNC"
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

# NOTE: must consolidate logs when changing run parent dir
export SPARK_JANELIA_ARGS="--consolidate_logs --run_parent_dir /groups/flyem/data/trautmane/spark_logs"
export LSF_PROJECT="${BILL_TO}"
export RUNTIME="3:59"

export HOT_KNIFE_JAR="/groups/flyem/data/render/lib/hot-knife-0.0.4-SNAPSHOT.jar"
export FLINTSTONE="/groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh"

# --------------------------------------------------------------------
# Default N5 Parameters
# --------------------------------------------------------------------
export N5_SAMPLE_PATH="/nrs/flyem/render/n5/${RENDER_OWNER}"
export N5_HEIGHT_FIELDS_COMPUTED_DATASET="/heightfields/${RENDER_PROJECT}/pass1/s1"
export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass1"
export N5_HEIGHT_FIELDS_DOWNSAMPLING_FACTORS="6,6,1"
export N5_FLAT_DATASET_ROOT="/flat/${RENDER_PROJECT}"
export N5_FLAT_RAW_DATASET="${N5_FLAT_DATASET_ROOT}/raw/s0"

PADDED_MIN_SEC_NUM=$(printf %02d "${MIN_SEC_NUM}")
PADDED_MAX_SEC_NUM=$(printf %02d "${MAX_SEC_NUM}")
export N5_SURFACE_ROOT="/surface-align-VNC/${PADDED_MIN_SEC_NUM}-${PADDED_MAX_SEC_NUM}/run_${RUN_TIMESTAMP}"

# --------------------------------------------------------------------
# Tab Customizations
#   ( initially populated by running
#     /groups/flyem/data/alignment/flyem-alignment-ett/Z0720-07m/VNC/surface/print_tab_configs.sh )
# --------------------------------------------------------------------
case "${TAB}" in
  "Sec06")
    export ACQUIRE_TRIMMED_STACK="v5_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export ALIGN_STACK="v5_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v5_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec06/v3_acquire_trimmed_align_ic___20220406_104101"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec07")
    export ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export ALIGN_STACK="v4_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v4_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec07/v2_acquire_trimmed_align_ic___20220406_105604"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec08")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec08/v1_acquire_trimmed_align_ic___20220406_110928"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec09")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec09/v3_acquire_trimmed_align_ic___20220406_121510"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec10")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec10/v2_acquire_trimmed_align_ic___20220406_135057"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec11")
    export ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export ALIGN_STACK="v4_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v4_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec11/???"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec12")
    export ACQUIRE_TRIMMED_STACK="v7_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v5_acquire_trimmed"
    export ALIGN_STACK="v7_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v7_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec12/???"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec13")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec13/v2_acquire_trimmed_align_ic___20220406_144232"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec14")
    export ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export ALIGN_STACK="v4_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v4_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec14/v4_acquire_trimmed_align_ic___20220414_154911"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec15")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec15/v2_acquire_trimmed_align_ic___20220415_051315"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec16")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec16/v2_acquire_trimmed_align_ic___20220412_190848"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec17")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec17/v2_acquire_trimmed_align_ic___20220414_211553"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec18")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec18/v1_acquire_trimmed_align_ic___20220412_141755"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec19")
    export ACQUIRE_TRIMMED_STACK="v6_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v5_acquire_trimmed"
    export ALIGN_STACK="v6_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v6_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec19/???"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec20")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec20/v2_acquire_trimmed_align_ic___20220406_165947"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec21")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec21/v1_acquire_trimmed_align_ic___20220406_165800"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec22")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec22/v1_acquire_trimmed_align_ic___20220412_110718"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec23")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec23/v1_acquire_trimmed_align_ic___20220411_202230"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec23_v1")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec23_v1/???"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec24")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec24/v2_acquire_trimmed_align_ic___20220412_223828"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec25")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec25/v3_acquire_trimmed_align_ic___20220412_192353"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec26")
    export ACQUIRE_TRIMMED_STACK="v7_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v6_acquire_trimmed"
    export ALIGN_STACK="v7_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v7_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec26/???"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec27")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec27/v2_acquire_trimmed_align_ic___20210919_074405"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec28")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec28/v2_acquire_trimmed_align_ic___20220411_213036"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec29")
    export ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export ALIGN_STACK="v4_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v4_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec29/???"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec30")
    export ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export ALIGN_STACK="v4_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v4_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec30/v4_acquire_trimmed_align_ic___20220307_133422"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec31")
    export ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export ALIGN_STACK="v4_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v4_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec31/v4_acquire_trimmed_align_ic___20220411_184328"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec32")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align_straightened"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_straightened_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec32/v1_acquire_trimmed_align_straightened_ic___20220307_142422"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec33")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire"
    export ALIGN_STACK="v1_acquire_trimmed_align_straightened"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_straightened_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec33/v1_acquire_trimmed_align_straightened_ic___20220307_142254"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec34")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align_straightened"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_straightened_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec34/v2_acquire_trimmed_align_straightened_ic___20220307_142207"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec35")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align_straightened"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_straightened_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec35/v2_acquire_trimmed_align_straightened_ic___20220210_101244"
    export N5_Z_CORR_OFFSET=""
  ;;
  "Sec36")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_align_straightened"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_align_straightened_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec36/v3_acquire_trimmed_align_straightened_ic___20220210_101421"
    export N5_Z_CORR_OFFSET=""
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