#!/bin/bash

set -e
umask 0002

if (( $# != 1 )); then
  echo "USAGE $0 <number of nodes>"
  exit 1
fi

N_NODES="${1}"

# --------------------------------------------------------------------
# setup export parameters

ARGV="\
--n5PathInput /nrs/flyem/render/n5/Z0720_07m_BR \
-i /flat/Sec26/raw -t 20 -b 20 \
-i /flat/Sec27/raw -t 20 -b 20 \
-i /flat/Sec28/raw -t 20 -b 20 \
-i /flat/Sec29/raw -t 20 -b 20 \
-i /flat/Sec30/raw -t 20 -b 20 \
-i /flat/Sec31/raw -t 20 -b 20 \
-i /flat/Sec32/raw -t 20 -b 20 \
-i /flat/Sec33/raw -t 20 -b 20 \
-i /flat/Sec34/raw -t 20 -b 20 \
-i /flat/Sec35/raw -t 20 -b 20 \
-i /flat/Sec36/raw -t 20 -b 20 \
-i /flat/Sec37/raw -t 20 -b 20 \
-i /flat/Sec38/raw -t 20 -b 20 \
-i /flat/Sec39/raw -t 20 -b 20 \
--n5TransformGroup ??? \
--n5PathOutput /nrs/flyem/render/??? \
--n5DatasetOutput ??? \
--blockSize 128,128,128"
# --normalizeContrast

HOT_KNIFE_JAR="???"
CLASS="org.janelia.saalfeldlab.hotknife.SparkExportAlignedSlabSeries"

# --------------------------------------------------------------------
# setup spark parameters (11 cores per worker)

export N_EXECUTORS_PER_NODE=2
export N_CORES_PER_EXECUTOR=5
export N_OVERHEAD_CORES_PER_WORKER=1
# Note: N_CORES_PER_WORKER=$(( (N_EXECUTORS_PER_NODE * N_CORES_PER_EXECUTOR) + N_OVERHEAD_CORES_PER_WORKER ))

# To distribute work evenly, recommended number of tasks/partitions is 3 times the number of cores.
export N_TASKS_PER_EXECUTOR_CORE=3
export N_CORES_DRIVER=1

export LSF_PROJECT="flyem"
export RUNTIME=10080 # minutes in 7 days

/groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh ${N_NODES} ${HOT_KNIFE_JAR} ${CLASS} ${ARGV}