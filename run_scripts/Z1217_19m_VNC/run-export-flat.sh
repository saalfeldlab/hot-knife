#!/bin/bash

# exit immediately if any step fails
set -e

# ensure files written by process are group writable
umask 0002

ABSOLUTE_SCRIPT=`readlink -m $0`
SCRIPT_DIR=`dirname ${ABSOLUTE_SCRIPT}`

if (( $# < 3 )); then
  echo """
USAGE:   $0 <raw dataset> <height fields> <number of nodes>

EXAMPLE: $0 /zcorr/Sec15___20200205_113313 /heightfields/Sec15_20200214_1730_s1_sp0_kh2_sp4b_fix2 30
"""
  exit 1
fi

# /zcorr/Sec15___20200205_113313
RAW_DATASET_S0="${1}/s0"

# 15
SLAB_ID=`echo "$1" | sed '
  s@.*/Sec@@
  s@__.*@@
'`

# /heightfields/Sec15_20200214_1730_s1_sp0_kh2_sp4b
HEIGHT_FIELDS="${2}"

N_NODES="${3}"        # 4, 10, 20

N5_INPUT_PATH="/nrs/flyem/tmp/VNC.n5"
N5_OUTPUT_PATH="/nrs/flyem/tmp/VNC-align.n5"
N5_OUTPUT_RAW="/align-v2/slab-${SLAB_ID}/raw"
N5_OUTPUT_DATASET="${N5_OUTPUT_RAW}/s0"

FULL_RAW_PATH="${N5_INPUT_PATH}${RAW_DATASET_S0}"
if [[ ! -d ${FULL_RAW_PATH} ]]; then
  echo "ERROR: missing full raw path ${FULL_RAW_PATH}"
  exit 1
fi

FULL_FIELD_PATH="${N5_INPUT_PATH}${HEIGHT_FIELDS}"
if [[ ! -d ${FULL_FIELD_PATH} ]]; then
  echo "ERROR: missing full height fields path ${FULL_FIELD_PATH}"
  exit 1
fi

ARGV="\
--n5RawPath=${N5_INPUT_PATH} \
--n5FieldPath=${N5_INPUT_PATH} \
--n5OutputPath=${N5_OUTPUT_PATH} \
--n5RawDataset=${RAW_DATASET_S0} \
--n5FieldGroup=${HEIGHT_FIELDS} \
--n5OutDataset=${N5_OUTPUT_DATASET} \
--padding=20 \
--blockSize=128,128,128"

#-----------------------------------------------------------
# These parameters need to be exported for use by flintstone script
export BILL_TO="flyem"
export RUNTIME="24:00" # set hard runtime limit to 1 day
export BASE_SPARK_LOGS_DIR="/groups/flyem/data/render/spark_output"
export SPARK_VERSION="2.3.1"

#SPARK_JAR="/groups/flyem/data/trautmane/hot-knife/hot-knife-0.0.4-SNAPSHOT.jar"
SPARK_JAR="/groups/flyem/data/trautmane/hot-knife/hot-knife-0.0.4-SNAPSHOT-surface-fitting.jar"
SPARK_CLASS="org.janelia.saalfeldlab.hotknife.SparkExportFlattenedVolume"

LOCAL_LOG_DIR="${SCRIPT_DIR}/logs"
mkdir -p ${LOCAL_LOG_DIR}
LOCAL_SPARK_LAUNCH_LOG="${LOCAL_LOG_DIR}/spark_launch.log"


${SCRIPT_DIR}/render-flintstone-lsd.sh ${N_NODES} ${SPARK_JAR} ${SPARK_CLASS} ${ARGV} | tee -a ${LOCAL_SPARK_LAUNCH_LOG}

echo """
When completed, view n5 export using:
  /groups/flyem/data/render/bin/n5-view.sh -i ${N5_OUTPUT_PATH} -d ${N5_OUTPUT_RAW}
"""