#!/bin/bash

# TODO: Use public version of spark-janelia-lsf. Changes in the modified version are:
#  1. Runtime argument made a string of form "hh:mm" instead of integer representing minutes
#  2. Turned off git updates check for now because it might hang silently waiting for the user prompt
SPARK_DEPLOY_CMD="/groups/saalfeld/home/pisarevi/workspace/spark-janelia/spark-janelia-lsf-modified"

USAGE="usage:
[TERMINATE=1] [RUNTIME=<hh:mm>] [TMPDIR=<tmp>] [N_EXECUTORS_PER_NODE=3] [MEMORY_PER_NODE=75] [N_DRIVER_THREADS=16] $0 <MASTER_JOB_ID|N_NODES> <JAR> <CLASS> <ARGV>

If job with \${MASTER_JOB_ID} does not exist, value will be interpreted as number of 
nodes (N_NODES), and a new Spark master will be started with N_NODES workers using

${SPARK_DEPLOY_CMD} -w \${N_NODES}

If master exists but no workers are present, this script will just exit with a non-zero return 
value."

FAILURE_CODE=1
RUNTIME="${RUNTIME:-}"
SPARK_VERSION="${SPARK_VERSION:-current}"

N_CORES_PER_NODE="${N_CORES_PER_NODE:-15}"
N_EXECUTORS_PER_NODE="${N_EXECUTORS_PER_NODE:-3}"
MEMORY_PER_NODE="${MEMORY_PER_NODE:-75}"
SPARK_OPTIONS="${SPARK_OPTIONS:-}"

N_DRIVER_THREADS="${N_DRIVER_THREADS:-16}"

if [ "$#" -lt "3" ]; then
    echo -e "Not enough arguments!" 1>&2
    echo -e "$USAGE" 1>&2
    exit $FAILURE_CODE
else
    ((++FAILURE_CODE))
fi


MASTER_JOB_ID=$1;     shift
JAR=`readlink -f $1`; shift
CLASS=$1;             shift

ARGV="$@"
MASTER_GREP=`bjobs -Xr -noheader -J master | grep -E  "^${MASTER_JOB_ID} +"`
EXIT_CODE=$?


if [ "$SPARK_VERSION" != "current" ] && [ "$SPARK_VERSION" != "2" ] && [ "$SPARK_VERSION" != "rc" ] && [ "$SPARK_VERSION" != "test" ]; then
    echo -e "Incorrect spark version specified. Possible values are: current, 2, rc, test"
    exit $FAILURE_CODE
else
    ((++FAILURE_CODE))
fi
SPARK_HOME_SUBFOLDER="spark-$SPARK_VERSION"
SPARK_VERSION_FLAG="-v $SPARK_VERSION"


if [ "$EXIT_CODE" -ne "0" ]; then
    echo -e "Master not present. Starting master with ${MASTER_JOB_ID} node(s)."
    echo -e "Not all workers are guaranteed to be present at the start of the job."
    echo -e "In order to make sure to have all workers present, start a Spark cluster"
    echo -e "and run this script with the appropriate master job id once all workers"
    echo -e "are running."
    echo -e
    echo -e "Start Spark server:"
    echo -e "${SPARK_DEPLOY_CMD} -w \${N_NODES} ${SPARK_VERSION_FLAG}"

    if [ "${MASTER_JOB_ID}" -gt "120" ]; then
        echo -e "It doesn't make sense to use ${MASTER_JOB_ID} nodes!"
        echo -e "${USAGE}"
        exit $FAILURE_CODE
    else
        ((++FAILURE_CODE))
    fi

    if [ -n "$RUNTIME" ]; then
        RUNTIME_FLAG="-t $RUNTIME"
    fi

    N_NODES=${MASTER_JOB_ID}
    SUBMISSION=`$SPARK_DEPLOY_CMD launch -n $N_NODES $SPARK_VERSION_FLAG $RUNTIME_FLAG`
    MASTER_JOB_ID=`echo $SUBMISSION | sed -r -n -e 's/.*Master submitted. Job ID is ([0-9]+).*/\1/p'`
    MASTER_GREP=`bjobs -Xr -noheader -J master | grep -E  "^${MASTER_JOB_ID} +"`
    echo -e "Master and workers submitted. Master job ID is ${MASTER_JOB_ID}"
fi


N_NODES=`bjobs | grep "W${MASTER_JOB_ID}" | wc -l`
TRIES_LEFT=5
while [ "$N_NODES" -lt "1" ] && [ "$TRIES_LEFT" -gt "0" ]; do
    echo -e "waiting for the workers... "
    ((--TRIES_LEFT))
    sleep 1s
    N_NODES=`bjobs | grep "W${MASTER_JOB_ID}" | wc -l`
done

if [ "$N_NODES" -lt "1" ]; then
    echo -e "No workers present for master ${MASTER_JOB_ID}!"
    echo -e "${USAGE}"
    exit $FAILURE_CODE
else
    ((++FAILURE_CODE))
fi


while [ -z "$(echo $MASTER_GREP | grep RUN)" ] ; do
    echo "Master node not ready yet - try again in five seconds..."
    sleep 5s
    MASTER_GREP=`bjobs -Xr -noheader -J master | grep -E  "^${MASTER_JOB_ID} +"`
done
HOST=`echo $MASTER_GREP | sed -r -n -e 's/.*\*([a-zA-Z0-9]+).*/\1/p'`

# --tmpdir uses $TMPDIR if set else /tmp
TMP_FILE=`mktemp --tmpdir`
# need this first line in tmp file
# http://llama.mshri.on.ca/faq-llama.html#tty
# or run qsub -S /bin/bash 
echo "#$ -S /bin/bash" >> $TMP_FILE
chmod +x $TMP_FILE
echo >> $TMP_FILE

export N_CORES_PER_EXECUTOR=$(($N_CORES_PER_NODE / $N_EXECUTORS_PER_NODE))
export MEMORY_PER_EXECUTOR=$(($MEMORY_PER_NODE / $N_EXECUTORS_PER_NODE))
export SPARK_HOME="${SPARK_HOME:-/usr/local/$SPARK_HOME_SUBFOLDER}"
export PATH="$SPARK_HOME:$SPARK_HOME/bin:$SPARK_HOME/sbin:$PATH"
export N_EXECUTORS=$(($N_NODES * $N_EXECUTORS_PER_NODE))
export PARALLELISM="$(($N_EXECUTORS * $N_CORES_PER_EXECUTOR * 3))"
export MASTER="spark://${HOST}:7077"

echo "export SPARK_HOME=${SPARK_HOME}" >> $TMP_FILE
echo "export PATH=$PATH" >> $TMP_FILE
echo "export PARALLELISM=$PARALLELISM" >> $TMP_FILE
echo "export MASTER=$MASTER" >> $TMP_FILE
echo "export N_EXECUTORS=$N_EXECUTORS" >> $TMP_FILE

echo -e
echo -e Environment:
echo -e "SPARK_HOME       $SPARK_HOME"
echo -e "PATH             ${PATH//:/\n                 }"
echo -e "PARALLELISM      $PARALLELISM"
echo -e "HOST             $HOST"
echo -e "MASTER           $MASTER"
echo -e "JOB_FILE         $TMP_FILE"
echo -e "JOB_NAME         $CLASS"

mkdir -p ~/.sparklogs

# --conf spark.eventLog.enabled=true does not work:
# 16/06/10 10:59:51 ERROR SparkContext: Error initializing SparkContext.
# java.io.FileNotFoundException: File file:/tmp/spark-events does not exist.

echo TIME_CMD="\"time \$SPARK_HOME/bin/spark-submit\"" >> $TMP_FILE
echo \$TIME_CMD --verbose \
          --conf spark.default.parallelism=$PARALLELISM \
          --conf spark.executor.cores=$N_CORES_PER_EXECUTOR \
          --conf spark.executor.memory=${MEMORY_PER_EXECUTOR}g \
          "${SPARK_OPTIONS}" \
          --class $CLASS \
          $JAR \
          $ARGV >> $TMP_FILE

if [ -n "${TERMINATE}" ]; then
    echo "${SPARK_DEPLOY_CMD} stopcluster -j ${MASTER_JOB_ID} -f"  >> $TMP_FILE
fi

if [ -n "$RUNTIME" ]; then
    RUNTIME_FLAG="-W $RUNTIME"
fi

echo -e "N_DRIVER_THREADS $N_DRIVER_THREADS"
if [ "$N_DRIVER_THREADS" -ne "1" ]; then
    SLOTS_FLAG="-n $N_DRIVER_THREADS"
fi

JOB_MESSAGE=`bsub $SLOTS_FLAG $RUNTIME_FLAG -J "$CLASS" -o ~/.sparklogs/$CLASS.o%J < $TMP_FILE`
JOB_ID=`echo ${JOB_MESSAGE} | sed -r 's/Job <([0-9]+)>.*/\1/'`
echo -e "JOB_ID           $JOB_ID"
echo -e "LOG_FILE         ~/.sparklogs/$CLASS.o$JOB_ID"

echo
echo -e $JOB_MESSAGE



