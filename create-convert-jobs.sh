#/bin/bash

# ./create-convert-jobs 22 24 25 26 27

VERSION="0.0.4-SNAPSHOT"
VOLUME="Z1217-19m/VNC"
PREFIX="/nrs/flyem/alignment"
padding=20
faceOffset=6


for slabId in "$@"
do
	topBot=(`cat $PREFIX/$VOLUME/Sec$slabId/flatten/tmp-flattening-level200/logs/amira-flattenTwoSidesBatchwise_0.log | grep "Avg low" | sed s/^[^1-9]*//g | sed s/[^1-9]*$//g`)
	top=`echo ${topBot[0]} | awk '{print int($1+0.5)}'`
	bot=`echo ${topBot[1]} | awk '{print int($1+0.5)}'`
	echo "top $top, bot $bot"

	convertScript="run-convert-${slabId}.sh"
	
	echo '#!/bin/bash' > $convertScript
	echo >> $convertScript
	echo 'OWN_DIR=`dirname "${BASH_SOURCE[0]}"`' >> $convertScript
	echo 'ABS_DIR=`readlink -f "$OWN_DIR"`' >> $convertScript
	echo >> $convertScript
	echo 'FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh' >> $convertScript
	echo "JAR=\$PWD/hot-knife-$VERSION.jar # this jar must be accessible from the cluster" >> $convertScript
	echo 'CLASS=org.janelia.saalfeldlab.hotknife.SparkConvertTiffSeriesToN5' >> $convertScript
	echo 'N_NODES=6' >> $convertScript
	echo >> $convertScript
	echo "URLFORMAT='$PREFIX/$VOLUME/Sec$slabId/flatten/flattened/zcorr.%05d-flattened.tif'" >> $convertScript
	echo "N5PATH='/nrs/flyem/data/tmp/$VOLUME.n5'" >> $convertScript
	echo "N5DATASET='slab-$slabId/raw/s0'" >> $convertScript
	echo "MIN='0,$(($top-$padding)),1'" >> $convertScript
	echo "SIZE='0,$(($bot-$top+$padding+$padding)),0'" >> $convertScript
	echo "BLOCKSIZE='128,128,128'" >> $convertScript
	echo >> $convertScript
	echo 'ARGV="\' >> $convertScript
	echo "--urlFormat '\$URLFORMAT' \\" >> $convertScript
	echo "--n5Path '\$N5PATH' \\" >> $convertScript
	echo "--n5Dataset '\$N5DATASET' \\" >> $convertScript
	echo "--min '\$MIN' \\" >> $convertScript
	echo "--size '\$SIZE' \\" >> $convertScript
	echo "--blockSize '\$BLOCKSIZE'\"" >> $convertScript
	echo >> $convertScript
	echo 'TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV' >> $convertScript

	chmod a+x $convertScript

	topFaceScript="run-extract-face-${slabId}-top.sh"

	echo '#!/bin/bash' > $topFaceScript
	echo >> $topFaceScript
	echo 'OWN_DIR=`dirname "${BASH_SOURCE[0]}"`' >> $topFaceScript
	echo 'ABS_DIR=`readlink -f "$OWN_DIR"`' >> $topFaceScript
	echo >> $topFaceScript
	echo 'FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh' >> $topFaceScript
	echo "JAR=\$PWD/hot-knife-$VERSION.jar" >> $topFaceScript
	echo 'CLASS=org.janelia.saalfeldlab.hotknife.SparkGenerateFaceScaleSpace' >> $topFaceScript
	echo 'N_NODES=10' >> $topFaceScript
	echo >> $topFaceScript
	echo "N5PATH='/nrs/flyem/data/tmp/$VOLUME.n5'" >> $topFaceScript
	echo "N5DATASETINPUT='/slab-$slabId/raw/s0'" >> $topFaceScript
	echo "N5GROUPOUTPUT='/slab-$slabId/top'" >> $topFaceScript
	echo "MIN='0,$(($padding+$faceOffset)),0'" >> $topFaceScript
	echo "SIZE='0,512,0'" >> $topFaceScript
	echo "BLOCKSIZE='1024,1024'" >> $topFaceScript
	echo >> $topFaceScript
	echo 'ARGV="\' >> $topFaceScript
	echo "--n5Path '\$N5PATH' \\" >> $topFaceScript
	echo "--n5DatasetInput '\$N5DATASETINPUT' \\" >> $topFaceScript
	echo "--n5GroupOutput '\$N5GROUPOUTPUT' \\" >> $topFaceScript
	echo "--min '\$MIN' \\" >> $topFaceScript
	echo "--size '\$SIZE' \\" >> $topFaceScript
	echo "--blockSize '\$BLOCKSIZE'\"" >> $topFaceScript
	echo >> $topFaceScript
	echo 'TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV' >> $topFaceScript

	chmod a+x $topFaceScript

	botFaceScript="run-extract-face-${slabId}-bot.sh"

	echo '#!/bin/bash' > $botFaceScript
	echo >> $botFaceScript
	echo 'OWN_DIR=`dirname "${BASH_SOURCE[0]}"`' >> $botFaceScript
	echo 'ABS_DIR=`readlink -f "$OWN_DIR"`' >> $botFaceScript
	echo >> $botFaceScript
	echo 'FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh' >> $botFaceScript
	echo "JAR=$PWD/hot-knife-$VERSION.jar" >> $botFaceScript
	echo 'CLASS=org.janelia.saalfeldlab.hotknife.SparkGenerateFaceScaleSpace' >> $botFaceScript
	echo 'N_NODES=10' >> $botFaceScript
	echo >> $botFaceScript
	echo "N5PATH='/nrs/flyem/data/tmp/$VOLUME.n5'" >> $botFaceScript
	echo "N5DATASETINPUT='/slab-$slabId/raw/s0'" >> $botFaceScript
	echo "N5GROUPOUTPUT='/slab-$slabId/bot'" >> $botFaceScript
	echo "MIN='0,$(($bot-$top+$padding-$faceOffset)),0'" >> $botFaceScript
	echo "SIZE='0,-512,0'" >> $botFaceScript
	echo "BLOCKSIZE='1024,1024'" >> $botFaceScript
	echo >> $botFaceScript
	echo 'ARGV="\' >> $botFaceScript
	echo "--n5Path '\$N5PATH' \\" >> $botFaceScript
	echo "--n5DatasetInput '\$N5DATASETINPUT' \\" >> $botFaceScript
	echo "--n5GroupOutput '\$N5GROUPOUTPUT' \\" >> $botFaceScript
	echo "--min '\$MIN' \\" >> $botFaceScript
	echo "--size '\$SIZE' \\" >> $botFaceScript
	echo "--blockSize '\$BLOCKSIZE'\"" >> $botFaceScript
	echo >> $botFaceScript
	echo 'TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV' >> $botFaceScript

	chmod a+x $botFaceScript
	
done
