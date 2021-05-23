#!/bin/bash

set -e

if (( $# != 1 )); then
  echo "USAGE $0 <start with pass (1-12)>"
  exit 1
fi

START_PASS="${1}"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

RUN_TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
SPARK_BATCH_WORK_DIR="${SCRIPT_DIR}/logs/spark_batch_${RUN_TIMESTAMP}"
SPARK_BATCH_LAUNCH_SCRIPT="${SPARK_BATCH_WORK_DIR}/00_launch_all.sh"

mkdir -p "${SPARK_BATCH_WORK_DIR}"

export BILL_TO="flyem"
export SPARK_JANELIA_TASK="generate-run"
export SPARK_JANELIA_ARGS="--run_parent_dir ${SPARK_BATCH_WORK_DIR}"

export SKIP_PRIOR_PASS_DIRECTORY_CHECK="true"

for PASS in $( seq "${START_PASS}" 12 ); do
  echo "-----------------------------------------------------------------------------
waiting to start setup for pass ${PASS} ...
"
  sleep 2
  "${SCRIPT_DIR}"/74_spark_surface_align_pass_n.sh "${PASS}"
done

echo "#!/bin/bash
" > "${SPARK_BATCH_LAUNCH_SCRIPT}"
chmod 755 "${SPARK_BATCH_LAUNCH_SCRIPT}"
echo "
created ${SPARK_BATCH_LAUNCH_SCRIPT}
"

COUNT=0
for Q_JOBS_SCRIPT in "${SPARK_BATCH_WORK_DIR}"/*/scripts/00-queue-lsf-jobs.sh; do

  PASS=$(( START_PASS + COUNT ))
  SHUTDOWN_JOB_NAME=$( grep "^SHUTDOWN_JOB_NAME" "${Q_JOBS_SCRIPT}" | cut -f2 -d'"' )

  if (( COUNT == 0 )); then
    echo "${Q_JOBS_SCRIPT}" >> "${SPARK_BATCH_LAUNCH_SCRIPT}"
  else
    echo "bsub -P ${BILL_TO} -J \"launch_pass_${PASS}\" -w \"done(${SHUTDOWN_JOB_NAME})\" -n 1 -W 5 ${Q_JOBS_SCRIPT}" >> "${SPARK_BATCH_LAUNCH_SCRIPT}"
  fi

  COUNT=$(( COUNT + 1 ))

done