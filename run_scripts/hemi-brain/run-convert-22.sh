#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh
JAR=$PWD/hot-knife-0.0.2-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkConvertTiffSeriesToN5
N_NODES=40

URLFORMAT='/nrs/flyem/data/Z0115-22_Sec22/flatten/flattened/zcorr.%05d-flattened.tif'
N5PATH='/nrs/flyem/data/tmp/Z0115-22.n5'
N5DATASET='slab-22/raw/s0'
MIN='-1,668,-1'
SIZE='-1,2094,-1'
BLOCKSIZE='128,128,128'

ARGV="\
--urlFormat '$URLFORMAT' \
--n5Path '$N5PATH' \
--n5Dataset '$N5DATASET' \
--min '$MIN' \
--size '$SIZE' \
--blockSize '$BLOCKSIZE'"

TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
