#!/bin/bash

UMASK=`umask`
umask 0002

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.ispim.SparkAlignAllChannels
N_NODES=2

ARGV="\
--n5Path=/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5 \
--maxEpsilon=0.01 \
--iterations=2000 \
--lambdaModel=0 \
--distance=3 \
--excludeIds=Pos005,Pos018,Pos019,Pos024,Pos025,Pos028,Pos033,Pos034,Pos035,Pos036,Pos047,Pos049 \
--excludeChannels=Ch405nm"

#TERMINATE=1 LSF_PROJECT="flyem" RUNTIME='72:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
TERMINATE=1 RUNTIME='72:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV

umask $UMASK

