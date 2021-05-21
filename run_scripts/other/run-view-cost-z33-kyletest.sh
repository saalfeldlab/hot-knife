#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.ViewCost

# n5-view -i /nrs/flyem/tmp/Z1217_33m_VNC.n5 -d /render/Sec33/v2_acquire_1_7270_sp2___20200804_184632 -o 323,-153,1

N5="/nrs/flyem/tmp/Z1217_33m_VNC.n5"
FULL_DATASET="/render/Sec33/v2_acquire_1_7270_sp2___20200804_184632"
COST_DATASET="/cost/Sec33/v2_acquire_1_7270_sp2___20200804_184632_kernelSize9"

ARGV="\
--n5Path=$N5 \
--cost=$COST_DATASET/s1 \
-i=$FULL_DATASET/s0"

echo "java -jar $JAR $CLASS $ARGV"
java -jar $JAR $CLASS $ARGV
