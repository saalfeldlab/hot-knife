#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.hotknife.SparkSeriesAlignSIFT
N_NODES=10

ARGV="\
--lambdaFilter 0.25 \
--lambdaModel 0.1 \
--maxEpsilon 20 \
--n5Input /groups/cosem/cosem/data/HeLa_Cell2_4x4x4nm/HeLa_Cell2_4x4x4nm_raw.n5 \
--n5Output /groups/cosem/cosem/saalfelds/HeLa_Cell2_4x4x4nm/HeLa_Cell2_4x4x4nm_raw.n5 \
--tmpPath /groups/cosem/cosem/saalfelds/HeLa_Cell2_4x4x4nm/tmp \
--distance 10 \
--minIntensity -1000 \
--maxIntensity 5000 \
--n5DatasetInput /volumes/raw/ch0 \
--n5DatasetOutput /volumes/raw/ch0 \
--iterations 1000"

TERMINATE=1 RUNTIME='24:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV

