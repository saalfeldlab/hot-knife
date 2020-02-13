#!/bin/bash

set -e

umask 0002

if (( $# < 2 )); then
  echo "USAGE $0 <Sec ID> <number of nodes>"
  exit 1
fi

SEC_ID="${1}"
N_NODES="${2}" # 4, 10, 20

N5_PATH="/nrs/flyem/tmp/VNC.n5"
COST_DIR="${N5_PATH}/cost"

unset SEC_DIRS
cd ${COST_DIR}
for SEC_DIR in `ls -d ${SEC_ID}* 2>/dev/null`; do
  if [[ -d ${SEC_DIR} ]]; then
    SEC_DIRS="${SEC_DIRS} ${SEC_DIR}"
  fi
done
cd -

NUM_MATCHING=`echo ${SEC_DIRS} | awk '{ print NF }'`

unset SEC_DIR

case "${NUM_MATCHING}" in
  '0')
  ;;

  '1')
  SEC_DIR=`echo ${SEC_DIRS}` # trim leading whitespace
  ;;

  *)
  PS3="Choose a cost directory: "
  select SEC_DIR in `echo ${SEC_DIRS}`; do
    break
  done

esac

S_ZERO_DATA_SET_DIR="${COST_DIR}/${SEC_DIR}/s0"

if [[ -d ${S_ZERO_DATA_SET_DIR} ]]; then
  DATA_SET="/cost/${SEC_DIR}"
else
  echo "ERROR: could not find: ${S_ZERO_DATA_SET_DIR}"
  exit 1
fi

#-----------------------------------------------------------
DEPLOY_DIR="/groups/flyem/data/trautmane/hot-knife"

FLINTSTONE=${DEPLOY_DIR}/flintstone/flintstone-lsd.sh
JAR=${DEPLOY_DIR}/hot-knife-0.0.4-SNAPSHOT.jar                       # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark

RUN_TIME=`date +"%Y%m%d_%H%M%S"`
#DATA_SET="${BASE_DATA_SET}___${RUN_TIME}"

SCALE_FACTORS="""
6,1,6
3,1,3
3,1,3
3,1,3
3,1,3
1,4,1
1,4,1
1,4,1
"""

ARGV="-n ${N5_PATH} -i ${DATA_SET}/s0 -b 128,128,128"
SCALE_INDEX=1
for SCALE_FACTOR in ${SCALE_FACTORS}; do
  ARGV="${ARGV} -o ${DATA_SET}/s${SCALE_INDEX} -f ${SCALE_FACTOR}"
  DOWNSAMPLE_DIR="${COST_DIR}/${SEC_DIR}/s${SCALE_INDEX}"
  if [[ -d ${DOWNSAMPLE_DIR} ]]; then
    echo "ERROR: downsample directory already exists: ${DOWNSAMPLE_DIR}"
    exit 1
  fi
  SCALE_INDEX=$((SCALE_INDEX + 1))
done

echo """Running with arguments:
${ARGV}
"""

NOHUP_FILE="nohup.${RUN_TIME}.out"
if [[ -d logs ]]; then
  NOHUP_FILE="logs/${NOHUP_FILE}"
fi

nohup ${FLINTSTONE} ${N_NODES} ${JAR} ${CLASS} ${ARGV} > ${NOHUP_FILE} 2>&1 &