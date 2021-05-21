#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkExportFlattenedVolume
N_NODES=20

SLAB_ID=3
RAW="/zcorr/Sec03___20200110_121405"
FIELD="/heightfields/Sec03_20200220_1108_kh0/s1"

ARGV="\
--n5RawPath=/nrs/flyem/tmp/VNC.n5 \
--n5FieldPath=/nrs/flyem/tmp/VNC.n5 \
--n5OutputPath=/nrs/flyem/tmp/VNC-align.n5 \
--n5RawDataset=$RAW/s0 \
--n5FieldGroup=$FIELD \
--n5OutDataset=/align/slab-$SLAB_ID/raw/s0 \
--padding=20 \
--blockSize=128,128,128"

TERMINATE=1 LSF_PROJECT="flyem" $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
