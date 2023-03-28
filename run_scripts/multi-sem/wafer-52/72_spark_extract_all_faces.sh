#!/bin/bash

set -e

if (( $# < 2 )); then
  echo "USAGE $0 <cut and slab> <number of nodes> (e.g. cut_036_slab_045 5)"
  exit 1
fi

CUT_AND_SLAB="${1}"
N_NODES="${2}" # wafer 52 cut_035_slab_001 top 20 took 2 minutes with 15 nodes
               # wafer 52 cut_035_slab_001 bot 21 took 2 minutes with 15 nodes

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

TOP_SURFACE_DEPTH=20
BOT_SURFACE_DEPTH=21

${SCRIPT_DIR}/72_spark_extract_face.sh ${CUT_AND_SLAB} ${N_NODES} top ${TOP_SURFACE_DEPTH}
sleep 5
${SCRIPT_DIR}/72_spark_extract_face.sh ${CUT_AND_SLAB} ${N_NODES} bot ${BOT_SURFACE_DEPTH}