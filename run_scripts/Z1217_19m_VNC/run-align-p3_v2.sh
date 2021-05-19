#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage
N_NODES=20

N5_PATH='/nrs/flyem/tmp/VNC-align.n5'
N5_GROUP_INPUT='/align-v2/align-2'
N5_GROUP_OUTPUT='/align-v2/align-3'
SCALE_INDEX='3'
STEP_SIZE='512'
LAMBDA_FILTER='0.1'
LAMBDA_MODEL='0.01'
MAX_EPSILON='40'

ARGV="\
--n5Path '$N5_PATH' \
--n5GroupInput '$N5_GROUP_INPUT' \
--n5GroupOutput '$N5_GROUP_OUTPUT' \
--scaleIndex '$SCALE_INDEX' \
--stepSize '$STEP_SIZE' \
--lambdaFilter '$LAMBDA_FILTER' \
--lambdaModel '$LAMBDA_MODEL' \
--maxEpsilon '$MAX_EPSILON'"

TERMINATE=1 LSF_PROJECT="flyem" $FLINTSTONE $N_NODES $JAR $CLASS $ARGV

