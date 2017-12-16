#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh
JAR=$PWD/hot-knife-0.0.2-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.hotknife.SparkAlignAffineGlobal
N_NODES=1

N5_PATH='/nrs/flyem/data/tmp/Z0115-22.n5'
N5_GROUP_OUTPUT='/align-0'
SCALE_INDEX='4'

ARGV="\
--n5Path '$N5_PATH' \
--n5GroupOutput '$N5_GROUP_OUTPUT' \
--scaleIndex '$SCALE_INDEX' \
-d '/slab-22/top/face' \
-d '/slab-22/bot/face' \
-d '/slab-23/top/face' \
-d '/slab-23/bot/face' \
-d '/slab-24/top/face' \
-d '/slab-24/bot/face' \
-d '/slab-25/top/face' \
-d '/slab-25/bot/face' \
-d '/slab-26/top/face' \
-d '/slab-26/bot/face' \
-d '/slab-27/top/face' \
-d '/slab-27/bot/face' \
-d '/slab-28/top/face' \
-d '/slab-28/bot/face' \
-d '/slab-29/top/face' \
-d '/slab-29/bot/face' \
-d '/slab-30/top/face' \
-d '/slab-30/bot/face' \
-d '/slab-31/top/face' \
-d '/slab-31/bot/face' \
-d '/slab-32/top/face' \
-d '/slab-32/bot/face' \
-d '/slab-33/top/face' \
-d '/slab-33/bot/face' \
-d '/slab-34/top/face' \
-d '/slab-34/bot/face'"

TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV

