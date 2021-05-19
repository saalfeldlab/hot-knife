#!/bin/bash

UMASK=`umask`
umask 0002

N5="/nrs/flyem/tmp/Z1217_33m_VNC.n5"
DATASET="/cost/Sec33/v2_acquire_1_7270_sp2___20200804_184632"

$HOME/spark/n5-spark/startup-scripts/spark-janelia/n5-downsample.py 5 -n "$N5" -i "$DATASET/s1" -o "$DATASET/s2" -b 128,128,128 -f 3,1,3
$HOME/spark/n5-spark/startup-scripts/spark-janelia/n5-downsample.py 5 -n "$N5" -i "$DATASET/s2" -o "$DATASET/s3" -b 128,128,128 -f 3,1,3
$HOME/spark/n5-spark/startup-scripts/spark-janelia/n5-downsample.py 2 -n "$N5" -i "$DATASET/s3" -o "$DATASET/s4" -b 128,128,128 -f 3,1,3
$HOME/spark/n5-spark/startup-scripts/spark-janelia/n5-downsample.py 2 -n "$N5" -i "$DATASET/s4" -o "$DATASET/s5" -b 128,128,128 -f 3,1,3

$HOME/spark/n5-spark/startup-scripts/spark-janelia/n5-downsample.py 1 -n "$N5" -i "$DATASET/s5" -o "$DATASET/s6" -b 128,128,128 -f 1,4,1
$HOME/spark/n5-spark/startup-scripts/spark-janelia/n5-downsample.py 1 -n "$N5" -i "$DATASET/s6" -o "$DATASET/s7" -b 128,128,128 -f 1,4,1
$HOME/spark/n5-spark/startup-scripts/spark-janelia/n5-downsample.py 1 -n "$N5" -i "$DATASET/s7" -o "$DATASET/s8" -b 128,128,128 -f 1,4,1

umask $UMASK

