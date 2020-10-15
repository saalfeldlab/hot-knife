#!/bin/bash

set -e

if (( $# < 3 )); then
  echo "USAGE $0 <project> <stack> <number of nodes> [region]"
  exit 1
fi

umask 0002

PROJECT="${1}"
STACK="${2}"
N_NODES="${3}"        # 4, 10, 20
REGION="${4:-VNC}"

OWNER="Z1217_19m"
if [[ "${REGION}" == "BR" ]]; then
  OWNER="Z1217_19m_BR"
fi

BILL_TO="flyem"

#-----------------------------------------------------------
BASE_URL="http://tem-services.int.janelia.org:8080/render-ws/v1"
DEPLOY_DIR="/groups/flyem/data/trautmane/hot-knife"

FLINTSTONE=${DEPLOY_DIR}/flintstone/flintstone-lsd.sh

JAR=${DEPLOY_DIR}/hot-knife-0.0.4-SNAPSHOT.jar                       # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkConvertRenderStackToN5
STACK_URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stack/${STACK}"

echo """
Pulling stack metadata from:
${STACK_URL}
"""

STACK_JSON=`./get-formatted-json.py "${STACK_URL}"`

MIN_X=`echo "${STACK_JSON}" | awk '/"minX"/ { gsub(",",""); print int($2) }'`
MAX_X=`echo "${STACK_JSON}" | awk '/"maxX"/ { gsub(",",""); print int($2) }'`
MIN_Y=`echo "${STACK_JSON}" | awk '/"minY"/ { gsub(",",""); print int($2) }'`
MAX_Y=`echo "${STACK_JSON}" | awk '/"maxY"/ { gsub(",",""); print int($2) }'`
MIN_Z=`echo "${STACK_JSON}" | awk '/"minZ"/ { gsub(",",""); print int($2) }'`
MAX_Z=`echo "${STACK_JSON}" | awk '/"maxZ"/ { gsub(",",""); print int($2) }'`

SIZE_X=$(( MAX_X - MIN_X ))
SIZE_Y=$(( MAX_Y - MIN_Y ))
SIZE_Z=$(( MAX_Z - MIN_Z ))

RUN_TIME=`date +"%Y%m%d_%H%M%S"`

# NOTE: with tile size 4096, need to keep z block size <= 64
ARGV="\
--n5Path='/nrs/flyem/tmp/${REGION}.n5'
--n5Dataset='/render/${PROJECT}/${STACK}___${RUN_TIME}'
--tileSize='4096,4096'
--min='${MIN_X},${MIN_Y},${MIN_Z}'
--size='${SIZE_X},${SIZE_Y},${SIZE_Z}'
--blockSize='128,128,64'
--baseUrl='${BASE_URL}'
--owner='${OWNER}'
--project='${PROJECT}'
--stack='${STACK}'
--factors='2,2,2'"

echo """Running with arguments:
${ARGV}
"""

NOHUP_FILE="nohup.${RUN_TIME}.out"

nohup $FLINTSTONE $N_NODES $JAR $CLASS $ARGV > logs/${NOHUP_FILE} 2>&1 &
