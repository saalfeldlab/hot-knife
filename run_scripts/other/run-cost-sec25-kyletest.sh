#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkComputeCost

N_NODES=6


N5="/nrs/flyem/tmp/VNC.n5"
DATASET="Sec25___20200106_082123"
RAW="Sec25___20200106_082123" # for visualization only

ARGV="\
--inputN5Path=$N5 \
--outputN5Path=$N5 \
--costN5Group=/kyle/cost/Sec25___20200106_082123_v02/s1 \
--inputN5Group=/zcorr/$RAW/s0 \
--costSteps=6,1,6"


LSF_PROJECT="flyem"

#echo "java -jar $JAR $CLASS $ARGV"

TERMINATE=1 RUNTIME='12:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
