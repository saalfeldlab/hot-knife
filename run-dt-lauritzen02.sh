#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkDistanceTransform
N_NODES=40


ARGV="\
--n5Path '/nrs/saalfeld/lauritzen/02/workspace.n5' \
--n5Dataset '/filtered/segmentation/multicut_more_features' \
--n5OutputPath '/nrs/saalfeld/lauritzen/02/workspace.n5' \
--n5OutputDataset '/dt-test' \
--blockSize '256,256,26' \
--padding '128,128,13' \
--resolution '4,4,40'"

TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
