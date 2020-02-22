#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.hotknife.SparkAlignAffineGlobal
N_NODES=1

N5_PATH='/nrs/flyem/tmp/VNC-align.n5'
N5_GROUP_OUTPUT='/align/align-0'
SCALE_INDEX='4'

ARGV="\
--n5Path '$N5_PATH' \
--n5GroupOutput '$N5_GROUP_OUTPUT' \
--scaleIndex '$SCALE_INDEX' \
-d '/align/slab-2/top/face' \
-d '/align/slab-2/bot/face' \
-d '/align/slab-3/top/face' \
-d '/align/slab-3/bot/face' \
-d '/align/slab-4/top/face' \
-d '/align/slab-4/bot/face' \
-d '/align/slab-5/top/face' \
-d '/align/slab-5/bot/face' \
-d '/align/slab-6/top/face' \
-d '/align/slab-6/bot/face' \
-d '/align/slab-7/top/face' \
-d '/align/slab-7/bot/face' \
-d '/align/slab-8/top/face' \
-d '/align/slab-8/bot/face' \
-d '/align/slab-9/top/face' \
-d '/align/slab-9/bot/face' \
-d '/align/slab-10/top/face' \
-d '/align/slab-10/bot/face' \
-d '/align/slab-11/top/face' \
-d '/align/slab-11/bot/face' \
-d '/align/slab-12/top/face' \
-d '/align/slab-12/bot/face' \
-d '/align/slab-13/top/face' \
-d '/align/slab-13/bot/face' \
-d '/align/slab-14/top/face' \
-d '/align/slab-14/bot/face' \
-d '/align/slab-15/top/face' \
-d '/align/slab-15/bot/face' \
-d '/align/slab-16/top/face' \
-d '/align/slab-16/bot/face' \
-d '/align/slab-17/top/face' \
-d '/align/slab-17/bot/face' \
-d '/align/slab-18/top/face' \
-d '/align/slab-18/bot/face' \
-d '/align/slab-19/top/face' \
-d '/align/slab-19/bot/face' \
-d '/align/slab-20/top/face' \
-d '/align/slab-20/bot/face' \
-d '/align/slab-21/top/face' \
-d '/align/slab-21/bot/face' \
-d '/align/slab-22/top/face' \
-d '/align/slab-22/bot/face' \
-d '/align/slab-23/top/face' \
-d '/align/slab-23/bot/face' \
-d '/align/slab-24/top/face' \
-d '/align/slab-24/bot/face' \
-d '/align/slab-25/top/face' \
-d '/align/slab-25/bot/face' \
-d '/align/slab-26/top/face' \
-d '/align/slab-26/bot/face'"

TERMINATE=1 LSF_PROJECT="flyem" $FLINTSTONE $N_NODES $JAR $CLASS $ARGV

