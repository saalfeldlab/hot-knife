#!/bin/bash

UMASK=`umask`
umask 0002

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.ispim.SparkExtractAllSIFTMatches
N_NODES=3

ARGV="\
--n5Path=/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5 \
--distance=10 \
--lambdaFilter=0.1 \
--maxEpsilon=5 \
--minIntensity=0 \
--maxIntensity=2000"

#TERMINATE=1 LSF_PROJECT="flyem" RUNTIME='72:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
TERMINATE=1 RUNTIME='72:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV

umask $UMASK

