#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh
JAR=$PWD/hot-knife-0.0.1-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkConvertTiffSeriesToN5
N_NODES=20

URLFORMAT='/groups/flyem/data/Z0115-22_Sec24/flatten2/flattened/zcorr.%05d-flattened.tif'
N5PATH='/nrs/flyem/data/tmp/Z0115-22.n5'
N5DATASET='slab-24'
MIN='-1,989,-1'
SIZE='-1,2886,-1'
BLOCKSIZE='128,128,128'

ARGV="\
--urlFormat '$URLFORMAT' \
--n5Path '$N5PATH' \
--n5Dataset '$N5DATASET' \
--min '$MIN' \
--size '$SIZE' \
--blockSize '$BLOCKSIZE'"

SPARK_VERSION=rc TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
