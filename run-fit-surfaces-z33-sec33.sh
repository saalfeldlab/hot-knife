#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkSurfaceFit
N_NODES=5

N5="/nrs/flyem/tmp/Z1217_33m_VNC.n5"
FULL_DATASET="/render/Sec33/v2_acquire_1_7270_sp2___20200804_184632"
COST_DATASET="/cost/Sec33/v2_acquire_1_7270_sp2___20200804_184632"
HEIGHTFIELD_DATASET="/heightfields/Sec33/v2_acquire_1_7270_sp2___20200804_184632"

#N5="/nrs/flyem/tmp/VNC.n5"
#DATASET="Sec25___20200106_082123"
#RAW="Sec25___20200106_082123" # for visualization only

ARGV="\
--n5Path=$N5 \
--n5FieldPath=$N5 \
--n5CostInput=$COST_DATASET \
--n5SurfaceOutput=$HEIGHTFIELD_DATASET \
--n5Raw=$FULL_DATASET \
--firstScale=8 \
--lastScale=1 \
--maxDeltaZ=0.25 \
--initMaxDeltaZ=0.5 \
--minDistance=2000 \
--maxDistance=5000"

LSF_PROJECT="flyem"

TERMINATE=1 RUNTIME='0:30' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
