#!/bin/bash

set -e

if (( $# < 5 )); then
  echo """
USAGE: $0 <executors> <wafer> <region> <launch jobs y|n> <serial-num> [serial-num] ...

Examples:
  $0  10 w61 r00 n 70 71
  $0  40 w61 r00 y 79
  $0  10 w61 r00 y 80 81 82 83 84 85 86 87 88 89

With 40 executors, w61_s079_r00 took 1 hour   1 minute.
With 20 executors, w61_s124_r00 took 1 hour  45 minutes.
With 15 executors, w61_s134_r00 took 2 hours  4 minutes.
With 10 executors, w61_s102_r00 took 3 hours 56 minutes.
"""
  exit 1
fi

EXECUTORS="${1}"
if ! [[ ${EXECUTORS} =~ ^[0-9]+$ ]] || (( EXECUTORS < 2 || EXECUTORS > 500 )); then
  echo "ERROR: executors argument must be an integer between 2 and 500"
  exit 1
fi

WAFER="${2}"
if [[ "$WAFER" != "w60" && "$WAFER" != "w61" ]]; then
  echo "ERROR: wafer must be 'w60' or 'w61'"
  exit 1
fi

REGION="${3}"
if [[ ! "$REGION" =~ ^r[0-9]{2}$ ]]; then
  echo "ERROR: REGION must be in the form rNN (e.g. r00, r11)"
  exit 1
fi

LAUNCH_JOBS="${4}"
shift 4

for STACK_NUMBER in "$@"; do

  if ! [[ "${STACK_NUMBER}" =~ ^[0-9]+$ ]]; then
      echo "Error: '${STACK_NUMBER}' is not a valid stack number."
      exit 1
  fi

  # 79 -> 079
  PADDED_STACK_NUMBER=$(printf "%03d" "${STACK_NUMBER}")

  # 079 -> w61_s079_r00
  RAW_STACK="${WAFER}_s${PADDED_STACK_NUMBER}_${REGION}"

  # w61_s079_r00 -> w61_serial_070_to_079
  PROJECT=$(awk -F'[_s]' '{w=$1; s=$3+0; lo=int(s/10)*10; hi=lo+9; printf "%s_serial_%03d_to_%03d", w, lo, hi}' <<<"${RAW_STACK}")

  CMD="./13_normalize_layer_intensity.sh ${EXECUTORS} ${PROJECT} ${RAW_STACK}"

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