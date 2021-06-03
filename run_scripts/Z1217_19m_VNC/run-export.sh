#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.hotknife.SparkExportAlignedSlabSeries
N_NODES=30

ARGV="\
--n5PathInput '/nrs/flyem/tmp/VNC-align.n5' \
--n5PathOutput '/nrs/flyem/tmp/VNC-export.n5' \
--n5TransformGroup '/align/align-12' \
--n5DatasetOutput '/2-26/s0' \
--blockSize '128,128,128' \
-i '/align/slab-2/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-3/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-4/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-5/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-6/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-7/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-8/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-9/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-10/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-11/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-12/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-13/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-14/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-15/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-16/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-17/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-18/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-19/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-20/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-21/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-22/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-23/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-24/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-25/raw/s0' \
-t 20 \
-b -20 \
-i '/align/slab-26/raw/s0' \
-t 20 \
-b -20"

TERMINATE=1 RUNTIME='72:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV

