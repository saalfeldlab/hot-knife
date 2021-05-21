#!/bin/bash

RAW="/zcorr/Sec15___20200205_113313"
FIELD="/heightfields/Sec15_20200214_1730_s1_sp0_kh2_sp4b_fix2"
N_NODES=30

# see run-export-flat.sh in this repo for details
/groups/flyem/data/sema/spark_example/run-export-flat.sh ${RAW} ${FIELD} ${N_NODES}
