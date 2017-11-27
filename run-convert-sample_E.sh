#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh
JAR=$PWD/hot-knife-0.0.2-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkConvertTiffSeriesToN5
N_NODES=20

URLFORMAT='/nrs/saalfeld/sample_E/%04d.jpg'
N5PATH='/nrs/saalfeld/sample_E/sample_E.n5'
N5DATASET='/volumes/raw'
MIN='0,0,0'
SIZE='-1,-1,1875'
BLOCKSIZE='128,128,13'
FIRSTSLICE='3955'

ARGV="\
--urlFormat '$URLFORMAT' \
--n5Path '$N5PATH' \
--n5Dataset '$N5DATASET' \
--min '$MIN' \
--size '$SIZE' \
--blockSize '$BLOCKSIZE' \
--firstSlice '$FIRSTSLICE'"

TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
