#!/bin/bash

set -e

DEFAULT_BATCH_SIZE=10

usage() {
  echo "
USAGE: $0 --num-exec <number> --wafer <wafer> --region <region> --serial <serial-num> [serial-num] ... [--batch-size <number>]
       $0 --num-exec <number> --sofima-csv <csv file> [--batch-size <number>]

  --num-exec    maximum number of executors for each batch job (2 to 500)
  --wafer       wafer, e.g. w61
  --region      region, e.g. r00
  --serial      one or more serial numbers, e.g. --serial 71 72 (can also be repeated)
  --sofima-csv  file listing the raw stacks to process (e.g. w61_s070_r00 or w61-s070-r00) separated by
                commas, spaces, or new lines, e.g. EM_recon_pipeline/src/scripts/msem_60_61/google_cloud/pipeline/sofima/sofima_complete_20261113a.csv
                (the wafer, region, and serial number of each stack are read from the file,
                so --wafer, --region, and --serial are not used with it)
  --batch-size  maximum number of stacks in each batch job (default: ${DEFAULT_BATCH_SIZE}), the stacks are split
                into as many batch jobs as needed

Exports the max heightfield (bottom surface) of each stack as a 3D uint8 surface volume
that neuroglancer can display over the ic3d pixel export, e.g.
  gs://janelia-spark-test/hess_wafers_60_61_export/heightfields_v4_surface/w61_serial_070_to_079/w61_s070_r00_gc_bc_par_cc_aso_3d_s3i/max

Examples:
  $0 --num-exec 10 --wafer w61 --region r00 --serial 70
  $0 --num-exec 10 --wafer w61 --region r00 --serial 71 72
  $0 --num-exec 10 --sofima-csv sofima_complete_20261113a.csv

Notes:
  - run after 51_gen_cost_and_heightfields_n5_batch.sh has finished for the stacks
  - existing surface volumes are not overwritten (remove them first to rebuild them)
  - Dataproc dynamic allocation is enabled, so --num-exec is the most executors a job will use
  - all of the batch jobs are submitted at once (you are asked to confirm when there is more than one)
"
  exit 1
}

if (( $# < 1 )); then
  usage
fi

#-----------------------------------------------------------
# parse named parameters

NUM_EXECUTORS=""
WAFER=""
REGION=""
SERIAL_NUMS=()
SOFIMA_CSV=""
BATCH_SIZE="${DEFAULT_BATCH_SIZE}"

requireValue() {
  if [[ -z "${2}" || "${2}" == --* ]]; then
    echo "ERROR: ${1} requires a value"
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "${1}" in
    --num-exec)
      requireValue "${1}" "${2}"
      NUM_EXECUTORS="${2}"
      shift 2
      ;;
    --wafer)
      requireValue "${1}" "${2}"
      WAFER="${2}"
      shift 2
      ;;
    --region)
      requireValue "${1}" "${2}"
      REGION="${2}"
      shift 2
      ;;
    --serial)
      requireValue "${1}" "${2}"
      shift
      # every value up to the next --parameter is a serial number
      while [[ $# -gt 0 && "${1}" != --* ]]; do
        SERIAL_NUMS+=("${1}")
        shift
      done
      ;;
    --sofima-csv)
      requireValue "${1}" "${2}"
      SOFIMA_CSV="${2}"
      shift 2
      ;;
    --batch-size)
      requireValue "${1}" "${2}"
      BATCH_SIZE="${2}"
      shift 2
      ;;
    *)
      echo "ERROR: unrecognized parameter '${1}'"
      usage
      ;;
  esac
done

#-----------------------------------------------------------
# validate parameters

if ! [[ ${NUM_EXECUTORS} =~ ^[0-9]+$ ]] || (( NUM_EXECUTORS < 2 || NUM_EXECUTORS > 500 )); then
  echo "ERROR: --num-exec must be an integer between 2 and 500 (not '${NUM_EXECUTORS}')"
  exit 1
fi

if ! [[ ${BATCH_SIZE} =~ ^[0-9]+$ ]] || (( BATCH_SIZE < 1 )); then
  echo "ERROR: --batch-size must be a positive integer (not '${BATCH_SIZE}')"
  exit 1
fi

if [[ -n "${SOFIMA_CSV}" ]]; then
  if [[ -n "${WAFER}" || -n "${REGION}" || ${#SERIAL_NUMS[@]} -gt 0 ]]; then
    echo "ERROR: --wafer, --region, and --serial cannot be combined with --sofima-csv"
    exit 1
  fi
else
  if [[ -z "${WAFER}" || -z "${REGION}" || ${#SERIAL_NUMS[@]} -eq 0 ]]; then
    echo "ERROR: --wafer, --region, and --serial are required unless --sofima-csv is specified"
    usage
  fi
  if [[ ! "${WAFER}" =~ ^w[0-9]+$ ]]; then
    echo "ERROR: --wafer must be in the form wNN (e.g. w61), not '${WAFER}'"
    exit 1
  fi
  if [[ ! "${REGION}" =~ ^r[0-9]{2}$ ]]; then
    echo "ERROR: --region must be in the form rNN (e.g. r00), not '${REGION}'"
    exit 1
  fi
  for SERIAL_NUM in "${SERIAL_NUMS[@]}"; do
    if ! [[ ${SERIAL_NUM} =~ ^[0-9]+$ ]]; then
      echo "ERROR: --serial values must be numbers (not '${SERIAL_NUM}')"
      exit 1
    fi
  done
fi

#-----------------------------------------------------------
# build the list of raw stacks (e.g. w61_s070_r00) to process

RAW_STACKS=()

if [[ -n "${SOFIMA_CSV}" ]]; then

  if [[ ! -f "${SOFIMA_CSV}" ]]; then
    echo "ERROR: --sofima-csv file ${SOFIMA_CSV} does not exist"
    exit 1
  fi

  # one stack per line with underscores instead of dashes, in file order with duplicates removed
  CSV_STACKS=$(tr ', \t\r' '\n\n\n\n' < "${SOFIMA_CSV}" | grep -v '^$' | tr '-' '_' | awk '! seen[$0]++')

  INVALID_STACKS=$(printf '%s\n' "${CSV_STACKS}" | grep -v -E '^w[0-9]+_s[0-9]{3}_r[0-9]{2}$' || true)
  if [[ -n "${INVALID_STACKS}" ]]; then
    echo "ERROR: ${SOFIMA_CSV} has values that are not <wafer>_s<serial>_r<region> raw stacks:"
    printf '%s\n' "${INVALID_STACKS}" | sed 's/^/  /'
    exit 1
  fi

  for RAW_STACK in ${CSV_STACKS}; do
    RAW_STACKS+=("${RAW_STACK}")
  done

  if (( ${#RAW_STACKS[@]} == 0 )); then
    echo "ERROR: ${SOFIMA_CSV} does not list any raw stacks"
    exit 1
  fi

  echo "
read ${#RAW_STACKS[@]} raw stack(s) from ${SOFIMA_CSV}"

else

  for SERIAL_NUM in "${SERIAL_NUMS[@]}"; do
    # force base 10 so that a zero padded serial (e.g. 070) is not treated as octal
    SERIAL_NUM_PADDED=$(printf "%03d" $(( 10#${SERIAL_NUM} )))
    RAW_STACK="${WAFER}_s${SERIAL_NUM_PADDED}_${REGION}"
    # skip a serial number that was specified more than once
    if [[ " ${RAW_STACKS[*]} " != *" ${RAW_STACK} "* ]]; then
      RAW_STACKS+=("${RAW_STACK}")
    fi
  done

fi

# Note: no need to check dataset existence here since SparkExportHeightFieldSurfaceMultiSemBatch does that up front

#-----------------------------------------------------------
RUN_TIMESTAMP=$(date +"%Y%m%d-%H%M%S")

N5_PATH="gs://janelia-spark-test/hess_wafers_60_61_export"

CLASS="org.janelia.saalfeldlab.hotknife.SparkExportHeightFieldSurfaceMultiSemBatch"
GS_JAR_URL="gs://janelia-spark-test/library/hot-knife-0.0.7-SNAPSHOT.jar"

#-----------------------------------------------------------
SPARK_EXEC_CORES=4

# For standard compute tier and spark runtime, total of spark.memory.offHeap.size,
# spark.executor.memory and spark.executor.memoryOverhead must be between 1024mb and 7424mb per core.
# Note that if not set, spark.executor.memoryOverhead defaults to 0.10 of spark.executor.memory.
SINGLE_CORE_MB=6700 # leave room for spark.executor.memoryOverhead, 6700 + 670 = 7370 < 7424
COMPUTE_TIER="standard"
DYNAMIC_ALLOCATION="spark.dynamicAllocation.enabled=true,spark.dynamicAllocation.maxExecutors=${NUM_EXECUTORS}"
DYNAMIC_ALLOCATION="${DYNAMIC_ALLOCATION},spark.dynamicAllocation.executorIdleTimeout=120"       # default is 60
DYNAMIC_ALLOCATION="${DYNAMIC_ALLOCATION},spark.dynamicAllocation.cachedExecutorIdleTimeout=240" # default is ?

SPARK_EXEC_MEMORY_MB=$(( SPARK_EXEC_CORES * SINGLE_CORE_MB ))

SPARK_PROPS="spark.dataproc.driver.compute.tier=${COMPUTE_TIER},spark.dataproc.executor.compute.tier=${COMPUTE_TIER}"
SPARK_PROPS="${SPARK_PROPS},spark.default.parallelism=240,spark.executor.instances=${NUM_EXECUTORS}"
SPARK_PROPS="${SPARK_PROPS},spark.executor.cores=${SPARK_EXEC_CORES},spark.executor.memory=${SPARK_EXEC_MEMORY_MB}mb"
SPARK_PROPS="${SPARK_PROPS},${DYNAMIC_ALLOCATION}"

# set executor disk size to avoid Insufficient DISKS_TOTAL_GB quota errors from Google
SPARK_PROPS="${SPARK_PROPS},spark.dataproc.executor.disk.size=250g"

# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/spark-runtime-1.1
# see https://cloud.google.com/dataproc-serverless/docs/concepts/versions/dataproc-serverless-versions
SPARK_VERSION="1.1"

OUTPUT_DIR="/Users/trautmane/Desktop/2026-11-msem/00-runs"
STAGE="52_hf_surface"
RUN_FILE="${OUTPUT_DIR}/run.$(date '+%Y%m%d').${STAGE}.txt"

#-----------------------------------------------------------
# split the stacks into batch jobs of up to BATCH_SIZE stacks

STACK_COUNT=${#RAW_STACKS[@]}
BATCH_COUNT=$(( (STACK_COUNT + BATCH_SIZE - 1) / BATCH_SIZE ))

echo "
${STACK_COUNT} stack(s) will be processed in ${BATCH_COUNT} batch job(s) of up to ${BATCH_SIZE} stack(s)
each batch job uses up to ${NUM_EXECUTORS} executors x ${SPARK_EXEC_CORES} cores"

if (( BATCH_COUNT > 1 )); then
  echo
  read -rp "Do you want to submit all ${BATCH_COUNT} batch jobs now? (y/n): " CONFIRM
  if [[ ! ${CONFIRM} =~ ^[Yy]$ ]]; then
    echo "
nothing was submitted
"
    exit 0
  fi
fi

for (( BATCH_INDEX = 0; BATCH_INDEX < BATCH_COUNT; BATCH_INDEX++ )); do

  BATCH_STACKS=("${RAW_STACKS[@]:$(( BATCH_INDEX * BATCH_SIZE )):${BATCH_SIZE}}")

  ARGV="--n5RootPath=${N5_PATH} --fieldName=max --blockSize=256,256,128"
  for RAW_STACK in "${BATCH_STACKS[@]}"; do
    ARGV="${ARGV} --raw ${RAW_STACK}"
  done

  # e.g. hf-surface-20261117-093012-w61-s070-r00-with-3
  NUMBER_OF_STACKS_MINUS_ONE=$(( ${#BATCH_STACKS[@]} - 1 ))
  BATCH_NAME=$(echo "hf-surface-${RUN_TIMESTAMP}-${BATCH_STACKS[0]}-with-${NUMBER_OF_STACKS_MINUS_ONE}" | sed "s/_/-/g")

  echo "
Running gcloud dataproc batches submit spark (batch job $(( BATCH_INDEX + 1 )) of ${BATCH_COUNT}) with:
  --region=us-east4
  --ttl=24h
  --jars=${GS_JAR_URL}
  --class=${CLASS}
  --batch=${BATCH_NAME}
  --version=${SPARK_VERSION}
  --properties=${SPARK_PROPS}
  --async
  --
  ${ARGV}

" | tee -a "${RUN_FILE}"

  # shellcheck disable=SC2086
  # use --async to return immediately
  gcloud dataproc batches submit spark \
  --region=us-east4 \
  --ttl=24h \
  --jars=${GS_JAR_URL} \
  --class=${CLASS} \
  --batch="${BATCH_NAME}" \
  --version=${SPARK_VERSION} \
  --properties="${SPARK_PROPS}" \
  --async \
  -- \
  ${ARGV} | tee -a "${RUN_FILE}"

done
