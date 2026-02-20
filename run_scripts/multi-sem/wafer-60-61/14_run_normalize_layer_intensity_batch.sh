#!/bin/bash

set -e

if (( $# < 3 )); then
  echo """
USAGE: $0 <max executors> <launch jobs y|n> <stack number> [stack number] ...

Examples:
  $0  10  n  70 71
  $0  40  y  79
  $0  10  y  80 81 82 83 84 85 86 87 88 89

With 40 max-executors, s079 took 61 minutes.
"""
  exit 1
fi

MAX_EXECUTORS="${1}"
LAUNCH_JOBS="${2}"
shift 2

WAFER="w61"
REGION="r00"

# shellcheck disable=SC2048
for STACK_NUMBER in $*; do

  if ! [[ "${STACK_NUMBER}" =~ ^[0-9]+$ ]]; then
      echo "Error: '${STACK_NUMBER}' is not a valid stack number."
      exit 1
  fi

  # 79 -> 079
  PADDED_STACK_NUMBER=$(printf "%03d" "${STACK_NUMBER}")

  # 079 -> w61_s079_r00
  STACK="${WAFER}_s${PADDED_STACK_NUMBER}_${REGION}"

  # w61_s079_r00 -> w61_serial_070_to_079
  PROJECT=$(awk -F'[_s]' '{w=$1; s=$3+0; lo=int(s/10)*10; hi=lo+9; printf "%s_serial_%03d_to_%03d", w, lo, hi}' <<<"${STACK}")

  CMD="./13_normalize_layer_intensity.sh ${MAX_EXECUTORS} ${PROJECT} ${STACK}"

  if [[ "${LAUNCH_JOBS}" == "y" ]]; then
    echo
    echo "Running the following in 10 seconds:"
    echo "  ${CMD}"
    echo
    sleep 10
    ${CMD}
  else
    echo "${CMD}"
  fi

done