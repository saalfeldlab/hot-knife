#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "$0")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

source "${SCRIPT_DIR}"/00_config.sh "NA"

umask 0002

DATASET_CSV="$1"
N_NODES="20"
export RUNTIME="233:59" # batches with ? slabs took between ? and ? hours to complete

if [[ ! -f ${DATASET_CSV} ]]; then
  echo "ERROR: csv file ${DATASET_CSV} not found"
  exit 1
fi

#-----------------------------------------------------------
# Spark executor setup with 11 cores per worker ...

export N_EXECUTORS_PER_NODE=2
export N_CORES_PER_EXECUTOR=5
# To distribute work evenly, recommended number of tasks/partitions is 3 times the number of cores.
#N_TASKS_PER_EXECUTOR_CORE=3
export N_OVERHEAD_CORES_PER_WORKER=1
#N_CORES_PER_WORKER=$(( (N_EXECUTORS_PER_NODE * N_CORES_PER_EXECUTOR) + N_OVERHEAD_CORES_PER_WORKER ))
export N_CORES_DRIVER=1

#-----------------------------------------------------------
RUN_TIME=$(date +"%Y%m%d_%H%M%S")
CLASS="org.janelia.saalfeldlab.hotknife.SparkExportFlattenedVolumeMultiSEMBatch"

ARGV="\
--n5RootPath=${N5_SAMPLE_PATH} \
--datasetCsv=${DATASET_CSV} \
--padding=3 \
--blockSize=128,128,64 \
--downsample"

LOG_DIR="logs/71_export_flat"
LOG_FILE="${LOG_DIR}/export_flat.${RUN_TIME}.out"
mkdir -p ${LOG_DIR}

# use shell group to tee all output to log file
{

  echo "Running with arguments:
${ARGV}
"
  /groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh $N_NODES $HOT_KNIFE_JAR $CLASS $ARGV
} 2>&1 | tee -a "${LOG_FILE}"

