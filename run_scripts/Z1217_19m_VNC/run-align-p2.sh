#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.hotknife.SparkPairAlignSIFTAverage
N_NODES=20

N5_PATH='/nrs/flyem/tmp/VNC-align.n5'
N5_GROUP_INPUT='/align/align-1'
N5_GROUP_OUTPUT='/align/align-2'
SCALE_INDEX='4'
STEP_SIZE='400'
LAMBDA_FILTER='0.1'
LAMBDA_MODEL='0.01'
MAX_EPSILON='50'

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

