#!/bin/bash

# ----------------------------------------------------------------------------
# Copy the surface zarr volumes from /nrs to Google cloud storage.

set -e

echo "
running $0 at $(date)
"

NRS_SURFACE_ALIGN="/nrs/hess/data/hess_wafers_60_61/export/zarr_datasets/surface-align"
GOOGLE_SURFACE_ALIGN="gs://janelia-spark-test/hess_wafers_60_61_export/surface-align"

RUN_AND_PASS="run_20260303_130000/pass00-scale1"

FULL_NRS="${NRS_SURFACE_ALIGN}/${RUN_AND_PASS}"
FULL_GOOGLE="${GOOGLE_SURFACE_ALIGN}/${RUN_AND_PASS}"

PARALLELISM=56

gcloud storage rsync ${FULL_NRS} ${FULL_GOOGLE} --recursive --parallelism-level=${PARALLELISM}