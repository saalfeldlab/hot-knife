#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=/groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh
JAR=/groups/flyem/data/render/lib/hot-knife-0.0.4b-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.hotknife.SparkExportAlignedSlabSeries
N_NODES=4

ARGV="\
--n5PathInput '/nrs/flyem/render/n5/Z0720_07m_BR' \
--n5PathOutput '/nrs/flyem/render/n5/Z0720_07m_BR' \
--n5TransformGroup '/surface_align_final/pass12' \
--n5DatasetOutput '/39-26/s0' \
--blockSize '128,128,128' \
--normalizeContrast \
-i '/flat/Sec39/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec38/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec37/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec36/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec35/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec34/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec33/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec32/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec31/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec30/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec29/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec28/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec27/raw' \
-t 20 \
-b -20 \
-i '/flat/Sec26/raw' \
-t 20 \
-b -20"

TERMINATE=1 RUNTIME='72:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV

