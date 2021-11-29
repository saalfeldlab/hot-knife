#!/bin/bash

set -e

if (( $# < 3 )); then
  echo "USAGE: $0 <tab> <min | max> <factors>   (e.g. Sec08 min 6,6,1)"
  exit 1
fi

TAB="$1"
MIN_OR_MAX="$2"
FACTORS="$3"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "${TAB}"

# /nrs/flyem/render/n5/Z0720_07m_BR/heightfields_fix/Sec08/pass1/min/attributes.json
HF_ATTR_FILE="${N5_SAMPLE_PATH}${N5_HEIGHT_FIELDS_FIX_DATASET}/${MIN_OR_MAX}/attributes.json"

if [[ ! -f ${HF_ATTR_FILE} ]]; then
  echo "ERROR: missing file ${HF_ATTR_FILE}"
  exit 1
fi

echo """
original contents of ${HF_ATTR_FILE} are:
"""
cat ${HF_ATTR_FILE} | /groups/flyem/data/render/bin/jq '.'

EXISTING_FACTORS=$(cat ${HF_ATTR_FILE} | /groups/flyem/data/render/bin/jq '. .downsamplingFactors')

if [ "${EXISTING_FACTORS}" == "null" ]; then

  cat ${HF_ATTR_FILE} | /groups/flyem/data/render/bin/jq ". + {downsamplingFactors:[${FACTORS}]}" > ${HF_ATTR_FILE}.fix
  mv ${HF_ATTR_FILE}.fix ${HF_ATTR_FILE}

  echo """
contents after adding downsamplingFactors are:
"""
cat ${HF_ATTR_FILE} | /groups/flyem/data/render/bin/jq '.'

  echo

fi
