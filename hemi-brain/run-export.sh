#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh
JAR=$PWD/hot-knife-0.0.2-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.hotknife.SparkExportAlignedSlabSeries
N_NODES=100

ARGV="\
--n5PathInput '/nrs/flyem/data/tmp/Z0115-22.n5' \
--n5PathOutput '/nrs/flyem/data/tmp/Z0115-22.export.n5' \
--n5TransformGroup '/align-13' \
--n5DatasetOutput '/22-34/s0' \
--blockSize '128,128,128' \
-i '/slab-22/raw/s0' \
-t 20 \
-b 2086 \
-i '/slab-23/raw/s0' \
-t 4 \
-b 2620 \
-i '/slab-24/raw/s0' \
-t 20 \
-b 2909 \
-i '/slab-25/raw/s0' \
-t 2 \
-b 2813 \
-i '/slab-26/raw/s0' \
-t 4 \
-b 2840 \
-i '/slab-27/raw/s0' \
-t 0 \
-b 2714 \
-i '/slab-28/raw/s0' \
-t 20 \
-b 2613 \
-i '/slab-29/raw/s0' \
-t 20 \
-b 2685 \
-i '/slab-30/raw/s0' \
-t 20 \
-b 2648 \
-i '/slab-31/raw/s0' \
-t 20 \
-b 2699 \
-i '/slab-32/raw/s0' \
-t 20 \
-b 2688 \
-i '/slab-33/raw/s0' \
-t 20 \
-b 2615 \
-i '/slab-34/raw/s0' \
-t 20 \
-b 2674"

TERMINATE=1 RUNTIME='24:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV

