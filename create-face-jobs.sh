#/bin/bash

# ./create-convert-jobs 22 24 25 26 27

VERSION="0.0.4-SNAPSHOT"
N5="/nrs/flyem/tmp/VNC-align.n5"
LSF_PROJECT="flyem"
padding=20
faceOffset=3

for slabId in "$@"
do
	topFaceScript="run-extract-face-${slabId}-top.sh"

	echo '#!/bin/bash' > $topFaceScript
	echo >> $topFaceScript
	echo 'OWN_DIR=`dirname "${BASH_SOURCE[0]}"`' >> $topFaceScript
	echo 'ABS_DIR=`readlink -f "$OWN_DIR"`' >> $topFaceScript
	echo >> $topFaceScript
	echo 'FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh' >> $topFaceScript
	echo "JAR=\$PWD/hot-knife-$VERSION.jar" >> $topFaceScript
	echo 'CLASS=org.janelia.saalfeldlab.hotknife.SparkGenerateFaceScaleSpace' >> $topFaceScript
	echo 'N_NODES=10' >> $topFaceScript
	echo >> $topFaceScript
	echo "N5PATH='$N5'" >> $topFaceScript
	echo "N5DATASETINPUT='/align/slab-$slabId/raw/s0'" >> $topFaceScript
	echo "N5GROUPOUTPUT='/align/slab-$slabId/top'" >> $topFaceScript
	echo "MIN='0,0,$(($padding+$faceOffset))'" >> $topFaceScript
	echo "SIZE='0,0,512'" >> $topFaceScript
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
	echo "TERMINATE=1 LSF_PROJECT=\"$LSF_PROJECT\" \$FLINTSTONE \$N_NODES \$JAR \$CLASS \$ARGV" >> $topFaceScript

	chmod a+x $topFaceScript

	botFaceScript="run-extract-face-${slabId}-bot.sh"

	echo '#!/bin/bash' > $botFaceScript
	echo >> $botFaceScript
	echo 'OWN_DIR=`dirname "${BASH_SOURCE[0]}"`' >> $botFaceScript
	echo 'ABS_DIR=`readlink -f "$OWN_DIR"`' >> $botFaceScript
	echo >> $botFaceScript
	echo 'FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh' >> $botFaceScript
	echo "JAR=\$PWD/hot-knife-$VERSION.jar" >> $botFaceScript
	echo 'CLASS=org.janelia.saalfeldlab.hotknife.SparkGenerateFaceScaleSpace' >> $botFaceScript
	echo 'N_NODES=10' >> $botFaceScript
	echo >> $botFaceScript
	echo "N5PATH='$N5'" >> $botFaceScript
	echo "N5DATASETINPUT='/align/slab-$slabId/raw/s0'" >> $botFaceScript
	echo "N5GROUPOUTPUT='/align/slab-$slabId/bot'" >> $botFaceScript
	echo "MIN='0,0,-$(($padding+$faceOffset))'" >> $botFaceScript
	echo "SIZE='0,0,-512'" >> $botFaceScript
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
	echo "TERMINATE=1 LSF_PROJECT=\"$LSF_PROJECT\" \$FLINTSTONE \$N_NODES \$JAR \$CLASS \$ARGV" >> $botFaceScript

	chmod a+x $botFaceScript
	
done
