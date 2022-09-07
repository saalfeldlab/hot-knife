#!/bin/bash

set -e
umask 0002

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "NA"

#Z_GRID_POSITIONS="--zGridPosition 361"

#Z_GRID_POSITIONS="--zGridPosition 37 --zGridPosition 55 --zGridPosition 73 --zGridPosition 91 --zGridPosition 109 --zGridPosition 127 --zGridPosition 145 --zGridPosition 163"
#Z_GRID_POSITIONS="${Z_GRID_POSITIONS} --zGridPosition 181 --zGridPosition 199 --zGridPosition 217 --zGridPosition 235 --zGridPosition 253 --zGridPosition 271 --zGridPosition 289 --zGridPosition 307"
#Z_GRID_POSITIONS="${Z_GRID_POSITIONS} --zGridPosition 325 --zGridPosition 343 --zGridPosition 379 --zGridPosition 397 --zGridPosition 415 --zGridPosition 433 --zGridPosition 451 --zGridPosition 469"
#Z_GRID_POSITIONS="${Z_GRID_POSITIONS} --zGridPosition 487 --zGridPosition 505 --zGridPosition 523 --zGridPosition 541 --zGridPosition 559 --zGridPosition 577 --zGridPosition 595 --zGridPosition 613"
#Z_GRID_POSITIONS="${Z_GRID_POSITIONS} --zGridPosition 631 --zGridPosition 649 --zGridPosition 667 --zGridPosition 685 --zGridPosition 703"

#Z_GRID_POSITIONS="--zGridPosition 18"
Z_GRID_POSITIONS="--zGridPosition 721 --zGridPosition 722"

#N_NODES=25
#N_NODES=180
N_NODES=100

export RUNTIME=8640 # minutes in 6 days

# --------------------------------------------------------------------
# setup export parameters

N5_PATH="/nrs/flyem/render/n5/Z0720_07m_VNC"
TRANSFORM_GROUP="/surface-align-VNC/06-37/run_20220902_140600/pass12" # TODO: validate/update
DATA_SET_OUTPUT="/06-36/20220907_112200/s0"                           # TODO: validate/update

# TODO: should Sec37 be included?
ARGV="\
--n5PathInput ${N5_PATH} \
-i /flat/Sec37/raw -t 20 -b -20 \
-i /flat/Sec36/raw -t 20 -b -20 \
-i /flat/Sec35/raw -t 20 -b -20 \
-i /flat/Sec34/raw -t 20 -b -20 \
-i /flat/Sec33/raw -t 20 -b -20 \
-i /flat/Sec32/raw -t 20 -b -20 \
-i /flat/Sec31/raw -t 20 -b -20 \
-i /flat/Sec30/raw -t 20 -b -20 \
-i /flat/Sec29/raw -t 20 -b -20 \
-i /flat/Sec28/raw -t 20 -b -20 \
-i /flat/Sec27/raw -t 20 -b -20 \
-i /flat/Sec26/raw -t 20 -b -20 \
-i /flat/Sec25/raw -t 20 -b -20 \
-i /flat/Sec24/raw -t 20 -b -20 \
-i /flat/Sec23/raw -t 20 -b -20 \
-i /flat/Sec22/raw -t 20 -b -20 \
-i /flat/Sec21/raw -t 20 -b -20 \
-i /flat/Sec20/raw -t 20 -b -20 \
-i /flat/Sec19/raw -t 20 -b -20 \
-i /flat/Sec18/raw -t 20 -b -20 \
-i /flat/Sec17/raw -t 20 -b -20 \
-i /flat/Sec16/raw -t 20 -b -20 \
-i /flat/Sec15/raw -t 20 -b -20 \
-i /flat/Sec14/raw -t 20 -b -20 \
-i /flat/Sec13/raw -t 20 -b -20 \
-i /flat/Sec12/raw -t 20 -b -20 \
-i /flat/Sec11/raw -t 20 -b -20 \
-i /flat/Sec10/raw -t 20 -b -20 \
-i /flat/Sec09/raw -t 20 -b -20 \
-i /flat/Sec08/raw -t 20 -b -20 \
-i /flat/Sec07/raw -t 20 -b -20 \
-i /flat/Sec06/raw -t 20 -b -20 \
--n5TransformGroup ${TRANSFORM_GROUP} \
--n5PathOutput ${N5_PATH} \
--n5DatasetOutput ${DATA_SET_OUTPUT} \
${Z_GRID_POSITIONS} \
--blockSize 128,128,128"
# --normalizeContrast
# --explainPlan        # use --explainPlan option to output debug info without running export

COMMIT="???" # TODO: update
HOT_KNIFE_JAR="/groups/flyem/data/render/lib/hot-knife-0.0.4-${COMMIT}-SNAPSHOT.jar"
CLASS="org.janelia.saalfeldlab.hotknife.SparkExportAlignedSlabSeries"

# --------------------------------------------------------------------
# setup spark parameters (11 cores per worker)

export N_EXECUTORS_PER_NODE=2
export N_CORES_PER_EXECUTOR=5
export N_OVERHEAD_CORES_PER_WORKER=1
# Note: N_CORES_PER_WORKER=$(( (N_EXECUTORS_PER_NODE * N_CORES_PER_EXECUTOR) + N_OVERHEAD_CORES_PER_WORKER ))

# To distribute work evenly, recommended number of tasks/partitions is 3 times the number of cores.
#export N_TASKS_PER_EXECUTOR_CORE=3

# changed default tasks per core from 3 to 12 based upon ...
#   with zBatch 1:40, each batch contains 72,216 blocks
#   total tasks = worker nodes * active cores per worker * tasks per core = 100 nodes * 10 active cores * 12 = 12,000 tasks
#   72,216 blocks / 12,000 tasks = roughly 6 blocks per task
#
# 6 blocks per task and more tasks should help with balancing
# setup will also still work for smaller woker node clusters (e.g. a 25 node cluster would have 24 blocks per task)
# only need to be careful for larger node clusters since you want to ensure multiple blocks per task
export N_TASKS_PER_EXECUTOR_CORE=12

export N_CORES_DRIVER=1

export LSF_PROJECT="flyem"

LOG_FILE=$(setupRunLog "export-aligned-slab-series-grid")

# use shell group to tee all output to log file
{

  echo """
Running with arguments:
${ARGV}
"""
  # shellcheck disable=SC2086
  ${FLINTSTONE} ${N_NODES} "${HOT_KNIFE_JAR}" ${CLASS} ${ARGV}

} 2>&1 | tee -a "${LOG_FILE}"
