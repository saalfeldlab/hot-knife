#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkSurfaceFit
N_NODES=5

N5="/nrs/flyem/tmp/VNC.n5"
DATASET="Sec02_20200206_152322"

ARGV="\
--n5Path=$N5 \
--n5FieldPath=$N5 \
--n5CostInput=/cost/$DATASET \
--n5SurfaceOutput=/heightfields/$DATASET \
--firstScale=7 \
--lastScale=1 \
--maxDeltaZ=0.25 \
--initMaxDeltaZ=0.5 \
--minDistance=3400 \
--maxDistance=3600"

TERMINATE=1 RUNTIME='0:30' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
