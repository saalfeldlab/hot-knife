#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkConvertCATMAIDStackToN5
N_NODES=60

ARGV="\
--urlFormat '/nrs/saalfeld/FAFB00/v14_align_tps_20170818_dmg/%6\$dx%7\$d/%1\$d/%5\$d/%5\$d.%1\$d.%8\$d.%9\$d.png' \
--n5Path '/groups/saalfeld/saalfeldlab/FAFB00/v14_align_tps_20170818_dmg.n5' \
--n5Dataset '/volumes/raw/s0' \
--tileSize '8192,8192' \
--min '10,10,0' \
--size '248156,133718,7062' \
--blockSize '256,256,26' \
--firstSlice 1"

TERMINATE=1 RUNTIME='72:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
