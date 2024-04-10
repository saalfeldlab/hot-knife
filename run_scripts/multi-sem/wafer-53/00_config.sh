#!/bin/bash

umask 0002

if (( $# != 1 )); then
  echo "USAGE: $0 <slab> (e.g. s070)"
  exit 1
fi

# Note: slab customizations are listed below defaults
export RAW_SLAB="${1}"

# --------------------------------------------------------------------
# Default Parameters
# --------------------------------------------------------------------
export RENDER_OWNER="hess_wafer_53d"
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
export RUNTIME="233:59"

export HOT_KNIFE_JAR="/groups/flyem/data/render/lib/hot-knife-0.0.5-SNAPSHOT.jar"
export FLINTSTONE="/groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh"

# --------------------------------------------------------------------
# Default N5 Parameters
# --------------------------------------------------------------------
export N5_SAMPLE_PATH="/nrs/hess/data/hess_wafer_53/export/${RENDER_OWNER}.n5"
export N5_HEIGHT_FIELDS_DOWNSAMPLING_FACTORS="2,2,1"
export N5_FLAT_DATASET_ROOT="/flat/${RAW_SLAB}"
export N5_FLAT_RAW_DATASET="${N5_FLAT_DATASET_ROOT}/raw/s0"
export N5_SURFACE_ROOT="/surface-align/run_${RUN_TIMESTAMP}"

# --------------------------------------------------------------------
# Slab Customizations
# --------------------------------------------------------------------
case "${RAW_SLAB}" in
  "s070_m104")
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s070_m104_align_ic2d_masked___20240406_081814"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s070_m104"
  ;;
  "s071_m331")
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s071_m331_align_ic2d_masked___20240406_081821"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s071_m331"
  ;;
  "s072_m150")
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s072_m150_align_ic2d_masked___20240406_081827"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s072_m150"
  ;;
  "s073_m079")
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s073_m079_align_ic2d_masked___20240406_081832"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s073_m079"
  ;;
  "s074_m265")
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s074_m265_align_ic2d_masked___20240406_081838"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s074_m265"
  ;;
  "s075_m119")
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s075_m119_align_ic2d_masked___20240406_081844"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s075_m119"
  ;;
  "s076_m033")
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s076_m033_align_ic2d_masked___20240406_081850"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s076_m033"
  ;;
  "s077_m286")
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s077_m286_align_ic2d_masked___20240406_081856"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s077_m286"
  ;;
  "s078_m279")
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s078_m279_align_ic2d_masked___20240406_081902"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s078_m279"
  ;;
  "s079_m214")
    export N5_Z_CORR_DATASET="/render/slab_070_to_079/s079_m214_align_ic2d_masked___20240406_081908"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s079_m214"
  ;;
  "s080_m174")
    export N5_Z_CORR_DATASET="/render/slab_080_to_089/s080_m174_align_ic2d_masked___20240405_224945"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s080_m174"
  ;;
  "s081_m049")
    export N5_Z_CORR_DATASET="/render/slab_080_to_089/s081_m049_align_ic2d_masked___20240405_224951"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s081_m049"
  ;;
  "s082_m190")
    export N5_Z_CORR_DATASET="/render/slab_080_to_089/s082_m190_align_ic2d_masked___20240405_224957"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s082_m190"
  ;;
  "s083_m029")
    export N5_Z_CORR_DATASET="/render/slab_080_to_089/s083_m029_align_ic2d_masked___20240405_225003"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s083_m029"
  ;;
  "s084_m069")
    export N5_Z_CORR_DATASET="/render/slab_080_to_089/s084_m069_align_ic2d_masked___20240405_225009"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s084_m069"
  ;;
  "s085_m031")
    export N5_Z_CORR_DATASET="/render/slab_080_to_089/s085_m031_align_ic2d_masked___20240405_225015"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s085_m031"
  ;;
  "s086_m181")
    export N5_Z_CORR_DATASET="/render/slab_080_to_089/s086_m181_align_ic2d_masked___20240405_225021"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s086_m181"
  ;;
  "s087_m155")
    export N5_Z_CORR_DATASET="/render/slab_080_to_089/s087_m155_align_ic2d_masked___20240405_225027"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s087_m155"
  ;;
  "s088_m291")
    export N5_Z_CORR_DATASET="/render/slab_080_to_089/s088_m291_align_ic2d_masked___20240405_225033"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s088_m291"
  ;;
  "s089_m045")
    export N5_Z_CORR_DATASET="/render/slab_080_to_089/s089_m045_align_ic2d_masked___20240405_225039"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s089_m045"
  ;;
  "s090_m114")
    export N5_Z_CORR_DATASET="/render/slab_090_to_099/s090_m114_align_ic2d_masked___20240405_225045"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s090_m114"
  ;;
  "s091_m246")
    export N5_Z_CORR_DATASET="/render/slab_090_to_099/s091_m246_align_ic2d_masked___20240405_225050"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s091_m246"
  ;;
  "s092_m189")
    export N5_Z_CORR_DATASET="/render/slab_090_to_099/s092_m189_align_ic2d_masked___20240405_225056"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s092_m189"
  ;;
  "s093_m228")
    export N5_Z_CORR_DATASET="/render/slab_090_to_099/s093_m228_align_ic2d_masked___20240405_225102"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s093_m228"
  ;;
  "s094_m059")
    export N5_Z_CORR_DATASET="/render/slab_090_to_099/s094_m059_align_ic2d_masked___20240405_225108"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s094_m059"
  ;;
  "s095_m221")
    export N5_Z_CORR_DATASET="/render/slab_090_to_099/s095_m221_align_ic2d_masked___20240405_225114"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s095_m221"
  ;;
  "s096_m132")
    export N5_Z_CORR_DATASET="/render/slab_090_to_099/s096_m132_align_ic2d_masked___20240405_225120"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s096_m132"
  ;;
  "s097_m149")
    export N5_Z_CORR_DATASET="/render/slab_090_to_099/s097_m149_align_ic2d_masked___20240405_225126"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s097_m149"
  ;;
  "s098_m154")
    export N5_Z_CORR_DATASET="/render/slab_090_to_099/s098_m154_align_ic2d_masked___20240405_225132"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s098_m154"
  ;;
  "s099_m233")
    export N5_Z_CORR_DATASET="/render/slab_090_to_099/s099_m233_align_ic2d_masked___20240405_225138"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s099_m233"
  ;;
  "s100_m164")
    export N5_Z_CORR_DATASET="/render/slab_100_to_109/s100_m164_align_ic2d_masked___20240406_074007"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s100_m164"
  ;;
  "s101_m313")
    export N5_Z_CORR_DATASET="/render/slab_100_to_109/s101_m313_align_ic2d_masked___20240406_074014"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s101_m313"
  ;;
  "s102_m240")
    export N5_Z_CORR_DATASET="/render/slab_100_to_109/s102_m240_align_ic2d_masked___20240406_074020"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s102_m240"
  ;;
  "s103_m236")
    export N5_Z_CORR_DATASET="/render/slab_100_to_109/s103_m236_align_ic2d_masked___20240406_074026"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s103_m236"
  ;;
  "s104_m323")
    export N5_Z_CORR_DATASET="/render/slab_100_to_109/s104_m323_align_ic2d_masked___20240406_074032"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s104_m323"
  ;;
  "s105_m397")
    export N5_Z_CORR_DATASET="/render/slab_100_to_109/s105_m397_align_ic2d_masked___20240406_074038"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s105_m397"
  ;;
  "s106_m180")
    export N5_Z_CORR_DATASET="/render/slab_100_to_109/s106_m180_align_ic2d_masked___20240406_074044"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s106_m180"
  ;;
  "s107_m192")
    export N5_Z_CORR_DATASET="/render/slab_100_to_109/s107_m192_align_ic2d_masked___20240406_074050"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s107_m192"
  ;;
  "s108_m157")
    export N5_Z_CORR_DATASET="/render/slab_100_to_109/s108_m157_align_ic2d_masked___20240406_074055"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s108_m157"
  ;;
  "s109_m351")
    export N5_Z_CORR_DATASET="/render/slab_100_to_109/s109_m351_align_ic2d_masked___20240406_074101"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s109_m351"
  ;;
  "s110_m141")
    export N5_Z_CORR_DATASET="/render/slab_110_to_119/s110_m141_align_ic2d_masked___20240405_225144"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s110_m141"
  ;;
  "s111_m117")
    export N5_Z_CORR_DATASET="/render/slab_110_to_119/s111_m117_align_ic2d_masked___20240405_225150"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s111_m117"
  ;;
  "s112_m213")
    export N5_Z_CORR_DATASET="/render/slab_110_to_119/s112_m213_align_ic2d_masked___20240405_225156"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s112_m213"
  ;;
  "s113_m293")
    export N5_Z_CORR_DATASET="/render/slab_110_to_119/s113_m293_align_ic2d_masked___20240405_225202"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s113_m293"
  ;;
  "s114_m094")
    export N5_Z_CORR_DATASET="/render/slab_110_to_119/s114_m094_align_ic2d_masked___20240405_225208"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s114_m094"
  ;;
  "s115_m242")
    export N5_Z_CORR_DATASET="/render/slab_110_to_119/s115_m242_align_ic2d_masked___20240405_225214"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s115_m242"
  ;;
  "s116_m341")
    export N5_Z_CORR_DATASET="/render/slab_110_to_119/s116_m341_align_ic2d_masked___20240405_225220"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s116_m341"
  ;;
  "s117_m023")
    export N5_Z_CORR_DATASET="/render/slab_110_to_119/s117_m023_align_ic2d_masked___20240405_225226"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s117_m023"
  ;;
  "s118_m092")
    export N5_Z_CORR_DATASET="/render/slab_110_to_119/s118_m092_align_ic2d_masked___20240405_225232"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s118_m092"
  ;;
  "s119_m169")
    export N5_Z_CORR_DATASET="/render/slab_110_to_119/s119_m169_align_ic2d_masked___20240405_225238"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s119_m169"
  ;;
  "s120_m324")
    export N5_Z_CORR_DATASET="/render/slab_120_to_129/s120_m324_align_ic2d_masked___20240405_225244"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s120_m324"
  ;;
  "s121_m217")
    export N5_Z_CORR_DATASET="/render/slab_120_to_129/s121_m217_align_ic2d_masked___20240405_225250"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s121_m217"
  ;;
  "s122_m325")
    export N5_Z_CORR_DATASET="/render/slab_120_to_129/s122_m325_align_ic2d_masked___20240405_225256"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s122_m325"
  ;;
  "s123_m357")
    export N5_Z_CORR_DATASET="/render/slab_120_to_129/s123_m357_align_ic2d_masked___20240405_225301"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s123_m357"
  ;;
  "s124_m129")
    export N5_Z_CORR_DATASET="/render/slab_120_to_129/s124_m129_align_ic2d_masked___20240405_225307"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s124_m129"
  ;;
  "s125_m336")
    export N5_Z_CORR_DATASET="/render/slab_120_to_129/s125_m336_align_ic2d_masked___20240405_225313"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s125_m336"
  ;;
  "s126_m013")
    export N5_Z_CORR_DATASET="/render/slab_120_to_129/s126_m013_align_ic2d_masked___20240405_225319"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s126_m013"
  ;;
  "s127_m232")
    export N5_Z_CORR_DATASET="/render/slab_120_to_129/s127_m232_align_ic2d_masked___20240405_225325"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s127_m232"
  ;;
  "s128_m282")
    export N5_Z_CORR_DATASET="/render/slab_120_to_129/s128_m282_align_ic2d_masked___20240405_225331"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s128_m282"
  ;;
  "s129_m318")
    export N5_Z_CORR_DATASET="/render/slab_120_to_129/s129_m318_align_ic2d_masked___20240405_225337"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s129_m318"
  ;;
esac

# ln -s min heightfields/.../min

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
