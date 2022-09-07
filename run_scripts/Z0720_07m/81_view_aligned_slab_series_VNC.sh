#!/bin/bash

umask 0002

JAVA="/groups/flyem/data/render/lib/jdks/8.0.275.fx-zulu/bin/java"
MAX_MEMORY=16000

set +e

if ! ${JAVA} -version 1>/dev/null 2>/dev/null; then
  JAVA="java"
  if ! ${JAVA} -version 1>/dev/null 2>/dev/null; then
    echo "ERROR: java is not installed"
    exit 1
  fi
fi

echo
${JAVA} -version

if type free 1>/dev/null 2>/dev/null; then
  FREE_MB=$(free -m | awk '/^Mem/ {print $4}')
  MAX_MEMORY=$(echo "(${FREE_MB}*0.8)/1" | bc)

#  if [[ "${MAX_MEMORY}" -gt "92000" ]]; then
#    MAX_MEMORY=92000
#  fi
fi

set -e

if [ -d /Volumes/flyem/render/lib ]; then
  FLYEM_DATA_ROOT="/Volumes/flyemdata"
else
  FLYEM_DATA_ROOT="/groups/flyem/data"
fi

JAR="${FLYEM_DATA_ROOT}/render/lib/hot-knife.latest-fat.jar"
CLASS="org.janelia.saalfeldlab.hotknife.ViewAlignedSlabSeries"

ARGV="--n5Path /nrs/flyem/render/n5/Z0720_07m_VNC -j /surface-align-VNC/06-37/run_20220902_140600/pass12"

N5_PATH="/nrs/flyem/render/n5/Z0720_07m_VNC"
TRANSFORM_GROUP="/surface-align-VNC/06-37/run_20220902_140600/pass12" # TODO: update this to latest pass12

# note: Sec37 is not included because it only exists to position Sec36 to brain
# TODO: should order be reversed (6 to 37 instead of 37 to 6)?
ARGV="\
--n5Path ${N5_PATH} \
-j ${TRANSFORM_GROUP} \
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
-i /flat/Sec06/raw -t 20 -b -20"

CMD="${JAVA} -Xmx${MAX_MEMORY}m -cp ${JAR} ${CLASS} ${ARGV}"

echo """
On ${HOSTNAME}. running:
  ${CMD}
"""

${CMD}
