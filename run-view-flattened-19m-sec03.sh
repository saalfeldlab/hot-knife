#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.ViewFlattenedSlab2

# n5-view -i /nrs/flyem/tmp/Z1217_33m_VNC.n5 -d /render/Sec33/v2_acquire_1_7270_sp2___20200804_184632 -o 323,-153,1

N5="/nrs/flyem/tmp/VNC.n5"
FULL_DATASET="/zcorr/Sec03___20200110_121405"
FIELD_DATASET="/heightfields_new/Sec03___20200110_121405"

ARGV="\
--n5Path=$N5 \
--n5FieldPath=$N5 \
--n5Field=$FIELD_DATASET \
--n5Raw=$FULL_DATASET \
--scale=1"

echo "java -jar $JAR $CLASS $ARGV"
#java -jar $JAR $CLASS $ARGV

n5-view -i $N5 -d $FULL_DATASET &
n5-view -i $N5 -d $FIELD_DATASET/s1/min &
n5-view -i $N5 -d $FIELD_DATASET/s1/max &
mvn exec:java -Dexec.mainClass="$CLASS" -Dexec.args="$ARGV"
