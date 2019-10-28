#ABS_DIR=`readlink -f "$OWN_DIR"`
ABS_DIR=`pwd`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkConvertRenderStackToN5
N_NODES=1

URLFORMAT='/nrs/flyem/alignment/Z1217-19m/VNC/Sec20/flatten/flattened/zcorr.%05d-flattened.tif'
N5PATH='/nrs/flyem/data/tmp/Z1217-19m/VNC.n5'
N5DATASET='slab-20/raw/s0'
MIN='0,720,1'
SIZE='0,3483,0'
BLOCKSIZE='128,128,128'

ARGV="\
--n5Path='/nrs/flyem/alignment/kyle/tmp/Z1217_33m.n5'
--n5Dataset='/Sec29'
--tileSize='9958,4375'
--min='4000,2000,128'
--size='512,512,16'
--blockSize='128,128,16'
--baseUrl='http://renderer-dev.int.janelia.org:8080/render-ws/v1'
--owner='Z1217_33m'
--project='Sec29'
--stack='v1_1_affine_1_12824'
--factors='2,2,2'"

TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
