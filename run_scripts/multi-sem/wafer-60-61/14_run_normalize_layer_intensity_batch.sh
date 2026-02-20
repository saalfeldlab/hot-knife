#!/bin/bash

# Utility script to simplify submission of multiple normalize layer intensity jobs.
#
# Copy this script locally (e.g. to export-batch.sh) so that STACK_NAMES_WITH_MAX_Z can be filled in
# with details from running list-stacks.sh on VM
# and MAX_EXECUTORS and LAUNCH_JOBS values can be set.

set -e

MAX_EXECUTORS=10       # 10 executors for w61_s??? pixel with ?? z layers took ? hours, ?? minutes
LAUNCH_JOBS="n"        # set to "y" to launch jobs, anything else to just print commands

# STACK_NAMES_WITH_MAX_Z examples:
#   w61_s080_r00_gc_par_align_ic2d with maxZ 89
#   w61_s095_r00_gc_par_align_ic2d with maxZ 82
STACK_NAMES_WITH_MAX_Z="
"

while read -r LINE; do
    [[ -z "${LINE}" ]] && continue

    if [[ ${LINE} =~ ^[[:space:]]*([a-zA-Z0-9]+)_s([0-9]{3})_([^ ]+).*maxZ[[:space:]]+([0-9]+) ]]; then

        WAFER=${BASH_REMATCH[1]}                                            # w61
        SERIAL_STRING=${BASH_REMATCH[2]}                                    # 080
        STACK="${BASH_REMATCH[1]}_s${BASH_REMATCH[2]}_${BASH_REMATCH[3]}"

        # ensure decimal math
        SERIAL_NUM=$((10#${SERIAL_STRING}))

        # compute 10-range
        START=$(( (SERIAL_NUM / 10) * 10 ))
        END=$(( START + 9 ))

        # zero-pad
        START=$(printf "%03d" "${START}")
        END=$(printf "%03d" "${END}")

        PROJECT="${WAFER}_serial_${START}_to_${END}"

        # <number of nodes> <render project> <raw stack>
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
    fi

done <<< "${STACK_NAMES_WITH_MAX_Z}"