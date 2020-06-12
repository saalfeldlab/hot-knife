#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkExportFlattenedVolume
N_NODES=30

SLAB_ID=15
RAW="/zcorr/Sec15___20200205_113313"
FIELD="/heightfields/Sec15_20200214_1730_s1_sp0_kh2_sp4b_fix2"

ARGV="\
--n5RawPath=/nrs/flyem/tmp/VNC.n5 \
--n5FieldPath=/nrs/flyem/tmp/VNC.n5 \
--n5OutputPath=/nrs/flyem/tmp/VNC-align.n5 \
--n5RawDataset=$RAW/s0 \
--n5FieldGroup=$FIELD \
--n5OutDataset=/align-v2/slab-$SLAB_ID/raw/s0 \
--padding=20 \
--blockSize=128,128,128"

TERMINATE=1 LSF_PROJECT="flyem" $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
