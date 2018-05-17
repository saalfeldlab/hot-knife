#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkConvertRenderStackToN5
N_NODES=20

ARGV="\
--baseUrl 'http://tem-services.int.janelia.org:8080/render-ws/v1' \
--owner 'saalfeld' \
--project 'MLOLP_Z1112_19R' \
--stack 'v_54_multi_hypothesis_poly_1_90077' \
--n5Path '/nrs/turaga/funkej/fib19/fib19.n5' \
--n5Dataset '/volumes/raw-aligned/s0' \
--tileSize '16384,8192' \
--tempTileSize '4096,4096' \
--min '0,0,1' \
--size '18928,8405,90078' \
--blockSize '256,256,16'"

TERMINATE=1 RUNTIME='72:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
