#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.hotknife.SparkAlignAffineGlobal
N_NODES=1

N5_PATH='/nrs/flyem/data/tmp/Z1217-19m/VNC.n5'
N5_GROUP_OUTPUT='/align-0'
SCALE_INDEX='4'

ARGV="\
--n5Path '$N5_PATH' \
--n5GroupOutput '$N5_GROUP_OUTPUT' \
--scaleIndex '$SCALE_INDEX' \
-d '/slab-19/top/face' \
-d '/slab-19/bot/face' \
-d '/slab-20/top/face' \
-d '/slab-20/bot/face' \
-d '/slab-21/top/face' \
-d '/slab-21/bot/face' \
-d '/slab-22/top/face' \
-d '/slab-22/bot/face' \
-d '/slab-23/top/face' \
-d '/slab-23/bot/face' \
-d '/slab-24/top/face' \
-d '/slab-24/bot/face' \
-d '/slab-25/top/face' \
-d '/slab-25/bot/face' \
-d '/slab-26/top/face' \
-d '/slab-26/bot/face'"

TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV

