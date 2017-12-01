#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

JAR=$PWD/hot-knife-0.0.2-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.ViewN5

N5PATH=${1}
N5DATASET=${2}

ARGV="\
--n5Path $N5PATH \
--n5Dataset $N5DATASET"

java -Xmx92g -cp $JAR $CLASS $ARGV

