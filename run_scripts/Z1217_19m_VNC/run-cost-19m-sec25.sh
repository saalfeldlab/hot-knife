#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkComputeCost

N_NODES=5

# n5-view -i /nrs/flyem/tmp/Z1217_33m_VNC.n5 -d /render/Sec33/v2_acquire_1_7270_sp2___20200804_184632 -o 323,-153,1

N5="/nrs/flyem/tmp/VNC.n5"
FULL_DATASET="/zcorr/Sec25___20200106_082123"
COST_DATASET="/cost_new/Sec25___20200106_082123_v2"

ARGV="\
--inputN5Path=$N5 \
--outputN5Path=$N5 \
--costN5Group=$COST_DATASET/s1 \
--inputN5Group=$FULL_DATASET/s0 \
--costSteps=6,1,6 \
--bandSize=50 \
--maxSlope=0.04 \
--slopeCorrBandFactor=5.5 \
--slopeCorrXRange=20"

LSF_PROJECT="flyem"

#echo "java -jar $JAR $CLASS $ARGV"

TERMINATE=1 RUNTIME='12:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
