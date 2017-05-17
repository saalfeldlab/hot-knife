#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone.sh
JAR=$PWD/target/hot-knife-0.0.1-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.RenderSlab
N_NODES=20

ARGV="\
--inputformat=/groups/saalfeld/home/saalfelds/tmp/dagmar/27/ken27-flattened-xy-%05d.tif \
--xpath=/groups/saalfeld/home/saalfelds/tmp/dagmar/26-03173.27-00303.rigid.tif.x.bin \
--ypath=/groups/saalfeld/home/saalfelds/tmp/dagmar/26-03173.27-00303.rigid.tif.y.bin \
--affine=0.99999748954962,-0.002240735249145,13.832151825285585,0.002240735249145,0.99999748954962,14.594295519243587 \
--width=5670 \
--height=6426 \
--first=280 \
--last=3280 \
--outputformat=/groups/saalfeld/home/saalfelds/tmp/dagmar/out/ken27-flattened-xy-%05d.tif"

SPARK_VERSION=rc TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
