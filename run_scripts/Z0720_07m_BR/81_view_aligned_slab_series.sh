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

SLABS="/flat/Sec26/raw /flat/Sec27/raw /flat/Sec28/raw /flat/Sec29/raw /flat/Sec30/raw \
/flat/Sec31/raw /flat/Sec32/raw /flat/Sec33/raw /flat/Sec34/raw /flat/Sec35/raw \
/flat/Sec36/raw /flat/Sec37/raw /flat/Sec38/raw /flat/Sec39/raw"

ARGV="--n5Path /nrs/flyem/render/n5/Z0720_07m_BR \
-j /surface_align_v9/pass12"

for SLAB in ${SLABS}; do
  ARGV="${ARGV} -i ${SLAB} -t 20 -b 20"
done

CMD="${JAVA} -Xmx${MAX_MEMORY}m -cp ${JAR} ${CLASS} ${ARGV}"

echo """
On ${HOSTNAME}. running:
  ${CMD}
"""

${CMD}
