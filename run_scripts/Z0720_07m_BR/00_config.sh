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
export N5_SURFACE_ROOT="/surface-align-BR/${PADDED_MIN_SEC_NUM}-${PADDED_MAX_SEC_NUM}/run_${RUN_TIMESTAMP}"

# --------------------------------------------------------------------
# Tab Customizations
# --------------------------------------------------------------------
case "${TAB}" in
  "Sec06")
    export ACQUIRE_TRIMMED_STACK="v1_acquire"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec06/v1_acquire_align_ic___20210826_125429"
    export N5_Z_CORR_OFFSET="-5317,-1497,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass2"
    # export N5_HEIGHT_FIELDS_DOWNSAMPLING_FACTORS="4,4,4"
  ;;
  "Sec07")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec07/v3_acquire_trimmed_align_ic___20210826_222827"
    export N5_Z_CORR_OFFSET="-6414,-2674,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass2"
    # export N5_HEIGHT_FIELDS_DOWNSAMPLING_FACTORS="4,4,4"
  ;;
  "Sec08")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec08/v1_acquire_trimmed_align_ic___20210826_224232"
    export N5_Z_CORR_OFFSET="-10519,-3277,1"
  ;;
  "Sec09")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec09/v1_acquire_trimmed_align_ic___20210827_000852"
    export N5_Z_CORR_OFFSET="-11395,-2719,1"
  ;;
  "Sec10")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec10/v1_acquire_trimmed_align_ic___20210826_115316"
    export N5_Z_CORR_OFFSET="-11100,-5154,1"
  ;;
  "Sec11")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec11/v2_acquire_trimmed_align_ic___20210917_215852"
    export N5_Z_CORR_OFFSET="-11584,-1709,1"
  ;;
  "Sec12")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec12/v2_acquire_trimmed_align_ic___20210918_073007"
    export N5_Z_CORR_OFFSET="-13511,-3438,1"
  ;;
  "Sec13")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec13/v1_acquire_trimmed_align_ic___20210827_104112"
    export N5_Z_CORR_OFFSET="-14706,-3167,1"
  ;;
  "Sec14")
    export ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export ALIGN_STACK="v4_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v4_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec14/v4_acquire_trimmed_align_ic___20210827_131509"
    export N5_Z_CORR_OFFSET="-13989,-5035,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass2"
  ;;
  "Sec15")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec15/v2_acquire_trimmed_align_ic___20210827_162059"
    export N5_Z_CORR_OFFSET="-13956,-4295,1"
  ;;
  "Sec16")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec16/v1_acquire_trimmed_align_ic___20210827_095239"
    export N5_Z_CORR_OFFSET="-15225,-2882,1"
  ;;
  "Sec17")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec17/v2_acquire_trimmed_align_ic___20210805_194345"
    export N5_Z_CORR_OFFSET="-13782,-2921,1"
  ;;
  "Sec18")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec18/v2_acquire_trimmed_align_ic___20210805_194428"
    export N5_Z_CORR_OFFSET="-14851,-5672,1"
  ;;
  "Sec19")
    export ACQUIRE_TRIMMED_STACK="v5_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export ALIGN_STACK="v5_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v5_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec19/v5_acquire_trimmed_align_ic___20210827_144044"
    export N5_Z_CORR_OFFSET="-17530,-2248,1"
  ;;
  "Sec20")
    export ACQUIRE_TRIMMED_STACK="v5_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export ALIGN_STACK="v5_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v5_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec20/v5_acquire_trimmed_align_ic___20210827_074700"
    export N5_Z_CORR_OFFSET="-20983,-2395,1"
  ;;
  "Sec21")
    export ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export ALIGN_STACK="v4_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v4_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec21/v4_acquire_trimmed_align_ic___20210825_143430"
    export N5_Z_CORR_OFFSET="-18913,-1683,1601"
  ;;
  "Sec22")
    export ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export ALIGN_STACK="v4_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v4_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec22/v4_acquire_trimmed_align_ic___20210825_210921"
    export N5_Z_CORR_OFFSET="-13748,-2571,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass2"
  ;;
  "Sec23")
    export ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export ALIGN_STACK="v4_acquire_trimmed_align_2"
    export INTENSITY_CORRECTED_STACK="v4_acquire_trimmed_align_2_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec23/v4_acquire_trimmed_align_2_ic___20210825_085238"
    export N5_Z_CORR_OFFSET="-20503,-1663,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass2"
  ;;
  "Sec24")
    export ACQUIRE_TRIMMED_STACK="v5_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export ALIGN_STACK="v5_acquire_trimmed_align_custom"
    export INTENSITY_CORRECTED_STACK="v5_acquire_trimmed_align_custom_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec24/v5_acquire_trimmed_align_custom_ic___20211020_074809"
    export N5_Z_CORR_OFFSET="-11068,-3162,1"
  ;;
  "Sec25")
    export ACQUIRE_TRIMMED_STACK="v5_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export ALIGN_STACK="v5_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v5_acquire_trimmed_align_ic"
    export N5_Z_CORR_DATASET="/z_corr/Sec25/v5_acquire_trimmed_align_ic___20210709_144057"
    export N5_Z_CORR_OFFSET="-20327,-4974,1"
  ;;
  "Sec26")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec26/v2_acquire_trimmed_align_ic_global_3___20210518_215946"
    export N5_Z_CORR_OFFSET="192,-2385,1316"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass2_preibischs"
  ;;
  "Sec27")
    export ACQUIRE_TRIMMED_STACK="v5_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v4_acquire_trimmed"
    export ALIGN_STACK="v5_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v5_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec27/v5_acquire_trimmed_align_ic_global_3___20210518_220105"
    export N5_Z_CORR_OFFSET="-330,-1695,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass4_preibischs"
  ;;
  "Sec28")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec28/v3_acquire_trimmed_align_ic_global_3___20210524_224915"
    export N5_Z_CORR_OFFSET="-17488,-4261,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass5_preibischs"
  ;;
  "Sec29")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec29/v3_acquire_trimmed_align_ic_global_3___20210519_074013"
    export N5_Z_CORR_OFFSET="-14395,-3719,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass4_preibischs"
  ;;
  "Sec30")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec30/v3_acquire_trimmed_align_ic_global_3___20210519_074213"
    export N5_Z_CORR_OFFSET="-14832,-3955,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass4_preibischs"
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
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass6_preibischs"
  ;;
  "Sec33")
    export ALIGN_STACK="v1_acquire_trimmed_sp1_adaptive_3"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_sp1_adaptive_3_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec33/v1_acquire_trimmed_sp1_adaptive_3_ic_global_3___20210519_111209"
    export N5_Z_CORR_OFFSET="291,-584,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass4_preibischs"
  ;;
  "Sec34")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_align_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec34/v2_acquire_trimmed_align_ic_global_3___20210519_112458"
    export N5_Z_CORR_OFFSET="-14640,-2987,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass4_preibischs"
  ;;
  "Sec35")
    export ALIGN_STACK="v1_acquire_trimmed_align_4"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align_4_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec35/v1_acquire_trimmed_align_4_ic_global_3___20210519_113030"
    export N5_Z_CORR_OFFSET="-299,-2304,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass4_preibischs"
  ;;
  "Sec36")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_sp3_adaptive"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_sp3_adaptive_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec36/v2_acquire_trimmed_sp3_adaptive_ic_global_3___20210519_114111"
    export N5_Z_CORR_OFFSET="-280,-673,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass4_preibischs"
  ;;
  "Sec37")
    export ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export ALIGN_STACK="v2_acquire_trimmed_sp1_adaptive"
    export INTENSITY_CORRECTED_STACK="v2_acquire_trimmed_sp1_adaptive_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec37/v2_acquire_trimmed_sp1_adaptive_ic_global_3___20210519_114754"
    export N5_Z_CORR_OFFSET="285,-224,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass2_preibischs"
  ;;
  "Sec38")
    export ACQUIRE_TRIMMED_STACK="v3_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="v2_acquire_trimmed"
    export ALIGN_STACK="v3_acquire_trimmed_sp1_adaptive"
    export INTENSITY_CORRECTED_STACK="v3_acquire_trimmed_sp1_adaptive_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec38/v3_acquire_trimmed_sp1_adaptive_ic_global_3___20210519_114936"
    export N5_Z_CORR_OFFSET="-1000,-2857,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass2_preibischs"
  ;;
  "Sec39")
    export ALIGN_STACK="v1_acquire_trimmed_sp1"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_sp1_ic_global_3"
    export N5_Z_CORR_DATASET="/z_corr/Sec39/v1_acquire_trimmed_sp1_ic_global_3___20210518_163431"
    export N5_Z_CORR_OFFSET="-1324,-2661,1"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/${RENDER_PROJECT}/pass3_preibischs"
  ;;
  "Sec40")
    export ACQUIRE_TRIMMED_STACK="v1_acquire_trimmed"
    export OLD_ACQUIRE_TRIMMED_STACK="TBD"
    export ALIGN_STACK="v1_acquire_trimmed_align"
    export INTENSITY_CORRECTED_STACK="v1_acquire_trimmed_align"
    export N5_Z_CORR_DATASET="/z_corr/Sec40/v1_acquire_trimmed_align___20211008_223626"
    export N5_Z_CORR_OFFSET="-4564,-2560,1"
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