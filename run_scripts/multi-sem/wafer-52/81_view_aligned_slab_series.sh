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

N5_PATH="/nrs/hess/render/export/hess.n5"
TRANSFORM_GROUP="/surface-align/run_20230330_092301/pass12"


ARGV="\
--n5Path ${N5_PATH} \
-j ${TRANSFORM_GROUP} \
-i /flat/cut_030_slab_026/raw -t 20 -b -21 \
-i /flat/cut_031_slab_006/raw -t 20 -b -21 \
-i /flat/cut_032_slab_013/raw -t 20 -b -21 \
-i /flat/cut_033_slab_033/raw -t 20 -b -21 \
-i /flat/cut_034_slab_020/raw -t 20 -b -21 \
-i /flat/cut_035_slab_001/raw -t 20 -b -21 \
-i /flat/cut_036_slab_045/raw -t 20 -b -21 \
--zoom 50"

CMD="${JAVA} -Xmx${MAX_MEMORY}m -cp ${JAR} ${CLASS} ${ARGV}"

echo """
On ${HOSTNAME}. running:
  ${CMD}
"""

${CMD}
