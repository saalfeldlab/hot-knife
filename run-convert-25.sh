#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh
JAR=$PWD/hot-knife-0.0.1-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkConvertTiffSeriesToN5
N_NODES=20

URLFORMAT='/nrs/flyem/data/Z0115-22_Sec25/flatten/flattened/zcorr.%05d-flattened.tif'
N5PATH='/nrs/flyem/data/tmp/Z0115-22.n5'
N5DATASET='slab-25'
MIN='-1,1150,-1'
SIZE='-1,2815,-1'
BLOCKSIZE='128,128,128'

ARGV="\
--urlFormat '$URLFORMAT' \
--n5Path '$N5PATH' \
--n5Dataset '$N5DATASET' \
--min '$MIN' \
--size '$SIZE' \
--blockSize '$BLOCKSIZE'"

SPARK_VERSION=rc TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
