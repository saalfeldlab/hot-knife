#!/bin/bash

set -e

if (( $# != 2 )); then
  echo "USAGE $0 <tab id> <number of nodes> (e.g. Sec39 10)"
  exit 1
fi

TAB="${1}"
N_NODES="${2}"

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
source "${SCRIPT_DIR}/00_config.sh" "${TAB}"

validateDirectoriesExist "${N5_SAMPLE_PATH}${N5_Z_CORR_DATASET}/s0" "${N5_SAMPLE_PATH}${N5_HEIGHT_FIELDS_FIX_DATASET}"

N5_HEIGHT_FIELDS_ATTRIBUTES="${N5_SAMPLE_PATH}${N5_HEIGHT_FIELDS_FIX_DATASET}/attributes.json"
if [[ ! -f "${N5_HEIGHT_FIELDS_ATTRIBUTES}" ]]; then
  echo '{"downsamplingFactors":[6.0,6.0,1.0]}' > "${N5_HEIGHT_FIELDS_ATTRIBUTES}"
  echo "
created default ${N5_HEIGHT_FIELDS_ATTRIBUTES}"
fi

ARGV="\
--n5RawPath=${N5_SAMPLE_PATH} \
--n5FieldPath=${N5_SAMPLE_PATH} \
--n5OutputPath=${N5_SAMPLE_PATH} \
--n5RawDataset=${N5_Z_CORR_DATASET}/s0 \
--n5FieldGroup=${N5_HEIGHT_FIELDS_FIX_DATASET} \
--n5OutDataset=${N5_FLAT_DATASET} \
--padding=${N5_SURFACE_PADDING} \
--blockSize=${N5_FLAT_BLOCK_SIZE}"

CLASS="org.janelia.saalfeldlab.hotknife.SparkExportFlattenedVolume"

LOG_FILE=$(setupRunLog "export-flat")

# use shell group to tee all output to log file
{

  echo "
Running with arguments:
${ARGV}
"
  # shellcheck disable=SC2086
  ${FLINTSTONE} ${N_NODES} "${HOT_KNIFE_JAR}" ${CLASS} ${ARGV}

} 2>&1 | tee -a "${LOG_FILE}"