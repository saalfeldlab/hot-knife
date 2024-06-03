#!/bin/bash

umask 0002

if (( $# != 1 )); then
  echo "USAGE: $0 <slab> (e.g. s001_m239)"
  exit 1
fi

# Note: slab customizations are listed below defaults
export RAW_SLAB="${1}"

# --------------------------------------------------------------------
# Default Parameters
# --------------------------------------------------------------------
export RENDER_OWNER="hess_wafer_53_center7"
export BILL_TO="hess"

RUN_TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
export RUN_TIMESTAMP

# --------------------------------------------------------------------
# Default Spark Setup (11 cores per worker)
# --------------------------------------------------------------------
export N_EXECUTORS_PER_NODE=2
export N_CORES_PER_EXECUTOR=5
export N_OVERHEAD_CORES_PER_WORKER=1
# Note: N_CORES_PER_WORKER=$(( (N_EXECUTORS_PER_NODE * N_CORES_PER_EXECUTOR) + N_OVERHEAD_CORES_PER_WORKER ))

# To distribute work evenly, recommended number of tasks/partitions is 3 times the number of cores.
export N_TASKS_PER_EXECUTOR_CORE=3

export N_CORES_DRIVER=1

# Write spark logs to backed-up flyem filesystem rather than user home so that they are readable by others for analysis.
# NOTE: must consolidate logs when changing run parent dir
export SPARK_JANELIA_ARGS="--consolidate_logs --run_parent_dir /groups/hess/hesslab/render/spark_output/${USER}"
export LSF_PROJECT="${BILL_TO}"
export RUNTIME="233:59"

export HOT_KNIFE_JAR="/groups/flyem/data/render/lib/hot-knife-0.0.5-SNAPSHOT.jar"
export FLINTSTONE="/groups/flyTEM/flyTEM/render/spark/spark-janelia/flintstone.sh"

# --------------------------------------------------------------------
# Default N5 Parameters
# --------------------------------------------------------------------
export N5_SAMPLE_PATH="/nrs/hess/data/hess_wafer_53/export/${RENDER_OWNER}.n5"
export N5_HEIGHT_FIELDS_DOWNSAMPLING_FACTORS="2,2,1"
export N5_FLAT_DATASET_ROOT="/flat_clahe/${RAW_SLAB}"
export N5_FLAT_RAW_DATASET="${N5_FLAT_DATASET_ROOT}/raw/s0"
export N5_SURFACE_ROOT="/surface-align/run_${RUN_TIMESTAMP}"

export ALL_SLABS="
          s001_m239 s002_m395 s003_m348 s004_m107 s005_m316 s006_m167 s007_m285 s008_m281 s009_m172
s010_m231 s011_m151 s012_m097 s013_m333 s014_m178 s015_m278 s016_m099 s017_m330 s018_m300 s019_m073
s020_m302 s021_m253 s022_m367 s023_m241 s024_m362 s025_m277 s026_m372 s027_m275 s028_m173 s029_m349
s030_m016 s031_m105 s032_m133 s033_m039 s034_m081 s035_m387 s036_m252 s037_m381 s038_m139 s039_m295
s040_m022 s041_m003 s042_m070 s043_m379 s044_m292 s045_m296 s046_m259 s047_m307 s048_m044 s049_m025
s050_m268 s051_m287 s052_m008 s053_m188 s054_m326 s055_m089 s056_m131 s057_m055 s058_m102 s059_m355
s060_m162 s061_m235 s062_m122 s063_m054 s064_m212 s065_m057 s066_m210 s067_m037 s068_m118 s069_m390
s070_m104 s071_m331 s072_m150 s073_m079 s074_m265 s075_m119 s076_m033 s077_m286 s078_m279 s079_m214
s080_m174 s081_m049 s082_m190 s083_m029 s084_m069 s085_m031 s086_m181 s087_m155 s088_m291 s089_m045
s090_m114 s091_m246 s092_m189 s093_m228 s094_m059 s095_m221 s096_m132 s097_m149 s098_m154 s099_m233
s100_m164 s101_m313 s102_m240 s103_m236 s104_m323 s105_m397 s106_m180 s107_m192 s108_m157 s109_m351
s110_m141 s111_m117 s112_m213 s113_m293 s114_m094 s115_m242 s116_m341 s117_m023 s118_m092 s119_m169
s120_m324 s121_m217 s122_m325 s123_m357 s124_m129 s125_m336 s126_m013 s127_m232 s128_m282 s129_m318
s130_m091 s131_m043 s132_m140 s133_m305 s134_m064 s135_m078 s136_m115 s137_m388 s138_m290 s139_m111
s140_m067 s141_m238 s142_m018 s143_m366 s144_m321 s145_m080 s146_m009 s147_m375 s148_m109 s149_m243
s150_m280 s151_m017 s152_m145 s153_m205 s154_m124 s155_m096 s156_m198 s157_m026 s158_m177 s159_m365
s160_m215 s161_m380 s162_m250 s163_m063 s164_m319 s165_m058 s166_m020 s167_m121 s168_m076 s169_m208
s170_m225 s171_m260 s172_m196 s173_m166 s174_m134 s175_m194 s176_m041 s177_m146 s178_m137 s179_m036
s180_m147 s181_m211 s182_m010 s183_m264 s184_m203 s185_m084 s186_m247 s187_m047 s188_m385 s189_m315
s190_m294 s191_m038 s192_m086 s193_m030 s194_m182 s195_m128 s196_m120 s197_m347 s198_m306 s199_m130
s200_m207 s201_m056 s202_m158 s203_m269 s204_m237 s205_m015 s206_m283 s207_m263 s208_m254 s209_m249
s210_m062 s211_m350 s212_m170 s213_m386 s214_m095 s215_m222 s216_m271 s217_m392 s218_m142 s219_m199
s220_m224 s221_m176 s222_m309 s223_m329 s224_m334 s225_m358 s226_m219 s227_m396 s228_m363 s229_m075
s230_m126 s231_m304 s232_m314 s233_m364 s234_m289 s235_m226 s236_m195 s237_m267 s238_m266 s239_m320
s240_m001 s241_m112 s242_m040 s243_m274 s244_m116 s245_m071 s246_m052 s247_m299 s248_m012 s249_m391
s250_m082 s251_m108 s252_m028 s253_m100 s254_m337 s255_m103 s256_m060 s257_m369 s258_m223 s259_m230
s260_m136 s261_m000 s262_m066 s263_m186 s264_m335 s265_m090 s266_m127 s267_m308 s268_m317 s269_m046
s270_m024 s271_m301 s272_m053 s273_m019 s274_m165 s275_m345 s276_m204 s277_m272 s278_m193 s279_m161
s280_m256 s281_m206 s282_m220 s283_m106 s284_m050 s285_m201 s286_m179 s287_m359 s288_m276 s289_m014
s290_m144 s291_m262 s292_m065 s293_m400 s294_m123 s295_m175 s296_m339 s297_m048 s298_m311 s299_m034
s300_m160 s301_m378 s302_m184 s303_m083 s304_m370 s305_m035 s306_m340 s307_m006 s308_m098 s309_m110
s310_m368 s311_m297 s312_m171 s313_m298 s314_m338 s315_m303 s316_m068 s317_m361 s318_m389 s319_m002
s320_m021 s321_m101 s322_m005 s323_m354 s324_m156 s325_m245 s326_m200 s327_m244 s328_m135 s329_m401
s330_m085 s331_m251 s332_m027 s333_m163 s334_m343 s335_m011 s336_m373 s337_m394 s338_m332 s339_m032
s340_m371 s341_m356 s342_m191 s343_m261 s344_m216 s345_m327 s346_m312 s347_m342 s348_m061 s349_m288
s350_m352 s351_m218 s352_m234 s353_m042 s354_m093 s355_m310 s356_m197 s357_m051 s358_m074 s359_m248
s360_m346 s361_m125 s362_m255 s363_m344 s364_m374 s365_m383 s366_m088 s367_m007 s368_m257 s369_m143
s370_m159 s371_m087 s372_m402 s373_m258 s374_m077 s375_m284 s376_m398 s377_m202 s378_m376 s379_m229
s380_m382 s381_m377 s382_m328 s383_m004 s384_m384 s385_m227 s386_m270 s387_m187 s388_m072 s389_m322
s390_m273 s391_m393 s392_m168 s393_m138 s394_m360 s395_m113 s396_m153 s397_m148 s398_m183 s399_m185
s400_m152 s401_m353 s402_m399
"

# --------------------------------------------------------------------
# Slab Customizations
# --------------------------------------------------------------------
case "${RAW_SLAB}" in
  "s001_m239")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_000_to_009/s001_m239_align_no35_horiz_avgshd_ic___20240504_084349"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_000_to_009/s001_m239"
  ;;
  "s002_m395")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_000_to_009/s002_m395_align_no35_horiz_avgshd_ic___20240504_084955"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_000_to_009/s002_m395"
  ;;
  "s003_m348")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_000_to_009/s003_m348_align_no35_horiz_avgshd_ic___20240504_084958"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_000_to_009/s003_m348"
  ;;
  "s004_m107")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_000_to_009/s004_m107_align_no35_horiz_avgshd_ic___20240504_085001"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_000_to_009/s004_m107"
  ;;
  "s005_m316")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_000_to_009/s005_m316_align_no35_horiz_avgshd_ic___20240504_085004"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_000_to_009/s005_m316"
  ;;
  "s006_m167")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_000_to_009/s006_m167_align_no35_horiz_avgshd_ic___20240504_085007"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_000_to_009/s006_m167"
  ;;
  "s007_m285")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_000_to_009/s007_m285_align_no35_horiz_avgshd_ic___20240504_085010"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_000_to_009/s007_m285"
  ;;
  "s008_m281")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_000_to_009/s008_m281_align_no35_horiz_avgshd_ic___20240504_085013"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_000_to_009/s008_m281"
  ;;
  "s009_m172")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_000_to_009/s009_m172_align_no35_horiz_avgshd_ic___20240504_085015"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_000_to_009/s009_m172"
  ;;
  "s010_m231")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_010_to_019/s010_m231_align_no35_horiz_avgshd_ic___20240504_085018"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_010_to_019/s010_m231"
  ;;
  "s011_m151")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_010_to_019/s011_m151_align_no35_horiz_avgshd_ic___20240504_085021"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_010_to_019/s011_m151"
  ;;
  "s012_m097")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_010_to_019/s012_m097_align_no35_horiz_avgshd_ic___20240504_085024"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_010_to_019/s012_m097"
  ;;
  "s013_m333")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_010_to_019/s013_m333_align_no35_horiz_avgshd_ic___20240504_085027"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_010_to_019/s013_m333"
  ;;
  "s014_m178")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_010_to_019/s014_m178_align_no35_horiz_avgshd_ic___20240504_085030"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_010_to_019/s014_m178"
  ;;
  "s015_m278")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_010_to_019/s015_m278_align_no35_horiz_avgshd_ic___20240504_085032"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_010_to_019/s015_m278"
  ;;
  "s016_m099")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_010_to_019/s016_m099_align_no35_horiz_avgshd_ic___20240504_085035"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_010_to_019/s016_m099"
  ;;
  "s017_m330")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_010_to_019/s017_m330_align_no35_horiz_avgshd_ic___20240504_085038"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_010_to_019/s017_m330"
  ;;
  "s018_m300")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_010_to_019/s018_m300_align_no35_horiz_avgshd_ic___20240504_085041"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_010_to_019/s018_m300"
  ;;
  "s019_m073")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_010_to_019/s019_m073_align_no35_horiz_avgshd_ic___20240504_085044"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_010_to_019/s019_m073"
  ;;
  "s020_m302")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_020_to_029/s020_m302_align_no35_horiz_avgshd_ic___20240504_085046"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_020_to_029/s020_m302"
  ;;
  "s021_m253")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_020_to_029/s021_m253_align_no35_horiz_avgshd_ic___20240504_085049"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_020_to_029/s021_m253"
  ;;
  "s022_m367")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_020_to_029/s022_m367_align_no35_horiz_avgshd_ic___20240504_085052"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_020_to_029/s022_m367"
  ;;
  "s023_m241")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_020_to_029/s023_m241_align_no35_horiz_avgshd_ic___20240504_085055"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_020_to_029/s023_m241"
  ;;
  "s024_m362")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_020_to_029/s024_m362_align_no35_horiz_avgshd_ic___20240504_085058"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_020_to_029/s024_m362"
  ;;
  "s025_m277")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_020_to_029/s025_m277_align_no35_horiz_avgshd_ic___20240504_085101"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_020_to_029/s025_m277"
  ;;
  "s026_m372")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_020_to_029/s026_m372_align_no35_horiz_avgshd_ic___20240504_085103"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_020_to_029/s026_m372"
  ;;
  "s027_m275")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_020_to_029/s027_m275_align_no35_horiz_avgshd_ic___20240504_085106"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_020_to_029/s027_m275"
  ;;
  "s028_m173")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_020_to_029/s028_m173_align_no35_horiz_avgshd_ic___20240504_085109"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_020_to_029/s028_m173"
  ;;
  "s029_m349")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_020_to_029/s029_m349_align_no35_horiz_avgshd_ic___20240504_085112"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_020_to_029/s029_m349"
  ;;
  "s030_m016")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_030_to_039/s030_m016_align_no35_horiz_avgshd_ic___20240504_085115"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s030_m016"
  ;;
  "s031_m105")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_030_to_039/s031_m105_align_no35_horiz_avgshd_ic___20240504_085118"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s031_m105"
  ;;
  "s032_m133")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_030_to_039/s032_m133_align_no35_horiz_avgshd_ic___20240504_085120"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s032_m133"
  ;;
  "s033_m039")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_030_to_039/s033_m039_align_no35_horiz_avgshd_ic___20240504_085123"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s033_m039"
  ;;
  "s034_m081")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_030_to_039/s034_m081_align_no35_horiz_avgshd_ic___20240504_085126"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s034_m081"
  ;;
  "s035_m387")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_030_to_039/s035_m387_align_no35_horiz_avgshd_ic___20240504_085129"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s035_m387"
  ;;
  "s036_m252")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_030_to_039/s036_m252_align_no35_horiz_avgshd_ic___20240504_085131"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s036_m252"
  ;;
  "s037_m381")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_030_to_039/s037_m381_align_no35_horiz_avgshd_ic___20240504_085134"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s037_m381"
  ;;
  "s038_m139")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_030_to_039/s038_m139_align_no35_horiz_avgshd_ic___20240504_085137"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s038_m139"
  ;;
  "s039_m295")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_030_to_039/s039_m295_align_no35_horiz_avgshd_ic___20240504_085140"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_030_to_039/s039_m295"
  ;;
  "s040_m022")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_040_to_049/s040_m022_align_no35_horiz_avgshd_ic___20240504_085143"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_040_to_049/s040_m022"
  ;;
  "s041_m003")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_040_to_049/s041_m003_align_no35_horiz_avgshd_ic___20240504_085146"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_040_to_049/s041_m003"
  ;;
  "s042_m070")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_040_to_049/s042_m070_align_no35_horiz_avgshd_ic___20240504_085148"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_040_to_049/s042_m070"
  ;;
  "s043_m379")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_040_to_049/s043_m379_align_no35_horiz_avgshd_ic___20240504_085151"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_040_to_049/s043_m379"
  ;;
  "s044_m292")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_040_to_049/s044_m292_align_no35_horiz_avgshd_ic___20240504_085154"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_040_to_049/s044_m292"
  ;;
  "s045_m296")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_040_to_049/s045_m296_align_no35_horiz_avgshd_ic___20240504_085157"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_040_to_049/s045_m296"
  ;;
  "s046_m259")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_040_to_049/s046_m259_align_no35_horiz_avgshd_ic___20240504_085200"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_040_to_049/s046_m259"
  ;;
  "s047_m307")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_040_to_049/s047_m307_align_no35_horiz_avgshd_ic___20240504_085202"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_040_to_049/s047_m307"
  ;;
  "s048_m044")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_040_to_049/s048_m044_align_no35_horiz_avgshd_ic___20240504_085205"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_040_to_049/s048_m044"
  ;;
  "s049_m025")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_040_to_049/s049_m025_align_no35_horiz_avgshd_ic___20240504_085208"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_040_to_049/s049_m025"
  ;;
  "s050_m268")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_050_to_059/s050_m268_align_no35_horiz_avgshd_ic___20240504_085211"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_050_to_059/s050_m268"
  ;;
  "s051_m287")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_050_to_059/s051_m287_align_no35_horiz_avgshd_ic___20240504_085214"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_050_to_059/s051_m287"
  ;;
  "s052_m008")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_050_to_059/s052_m008_align_no35_horiz_avgshd_ic___20240504_085216"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_050_to_059/s052_m008"
  ;;
  "s053_m188")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_050_to_059/s053_m188_align_no35_horiz_avgshd_ic___20240504_085219"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_050_to_059/s053_m188"
  ;;
  "s054_m326")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_050_to_059/s054_m326_align_no35_horiz_avgshd_ic___20240504_085222"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_050_to_059/s054_m326"
  ;;
  "s055_m089")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_050_to_059/s055_m089_align_no35_horiz_avgshd_ic___20240504_085225"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_050_to_059/s055_m089"
  ;;
  "s056_m131")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_050_to_059/s056_m131_align_no35_horiz_avgshd_ic___20240504_085228"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_050_to_059/s056_m131"
  ;;
  "s057_m055")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_050_to_059/s057_m055_align_no35_horiz_avgshd_ic___20240504_085230"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_050_to_059/s057_m055"
  ;;
  "s058_m102")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_050_to_059/s058_m102_align_no35_horiz_avgshd_ic___20240504_085233"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_050_to_059/s058_m102"
  ;;
  "s059_m355")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_050_to_059/s059_m355_align_no35_horiz_avgshd_ic___20240504_085236"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_050_to_059/s059_m355"
  ;;
  "s060_m162")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_060_to_069/s060_m162_align_no35_horiz_avgshd_ic___20240504_085239"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_060_to_069/s060_m162"
  ;;
  "s061_m235")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_060_to_069/s061_m235_align_no35_horiz_avgshd_ic___20240504_085242"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_060_to_069/s061_m235"
  ;;
  "s062_m122")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_060_to_069/s062_m122_align_no35_horiz_avgshd_ic___20240504_085244"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_060_to_069/s062_m122"
  ;;
  "s063_m054")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_060_to_069/s063_m054_align_no35_horiz_avgshd_ic___20240504_085247"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_060_to_069/s063_m054"
  ;;
  "s064_m212")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_060_to_069/s064_m212_align_no35_horiz_avgshd_ic___20240504_085250"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_060_to_069/s064_m212"
  ;;
  "s065_m057")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_060_to_069/s065_m057_align_no35_horiz_avgshd_ic___20240504_085253"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_060_to_069/s065_m057"
  ;;
  "s066_m210")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_060_to_069/s066_m210_align_no35_horiz_avgshd_ic___20240504_085256"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_060_to_069/s066_m210"
  ;;
  "s067_m037")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_060_to_069/s067_m037_align_no35_horiz_avgshd_ic___20240504_085258"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_060_to_069/s067_m037"
  ;;
  "s068_m118")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_060_to_069/s068_m118_align_no35_horiz_avgshd_ic___20240504_085301"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_060_to_069/s068_m118"
  ;;
  "s069_m390")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_060_to_069/s069_m390_align_no35_horiz_avgshd_ic___20240504_085304"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_060_to_069/s069_m390"
  ;;
  "s070_m104")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_070_to_079/s070_m104_align_no35_horiz_avgshd_ic___20240504_085307"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s070_m104"
  ;;
  "s071_m331")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_070_to_079/s071_m331_align_no35_horiz_avgshd_ic___20240504_085310"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s071_m331"
  ;;
  "s072_m150")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_070_to_079/s072_m150_align_no35_horiz_avgshd_ic___20240504_085313"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s072_m150"
  ;;
  "s073_m079")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_070_to_079/s073_m079_align_no35_horiz_avgshd_ic___20240504_085315"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s073_m079"
  ;;
  "s074_m265")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_070_to_079/s074_m265_align_no35_horiz_avgshd_ic___20240504_085318"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s074_m265"
  ;;
  "s075_m119")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_070_to_079/s075_m119_align_no35_horiz_avgshd_ic___20240504_085321"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s075_m119"
  ;;
  "s076_m033")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_070_to_079/s076_m033_align_no35_horiz_avgshd_ic___20240504_085324"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s076_m033"
  ;;
  "s077_m286")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_070_to_079/s077_m286_align_no35_horiz_avgshd_ic___20240504_085326"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s077_m286"
  ;;
  "s078_m279")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_070_to_079/s078_m279_align_no35_horiz_avgshd_ic___20240504_085329"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s078_m279"
  ;;
  "s079_m214")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_070_to_079/s079_m214_align_no35_horiz_avgshd_ic___20240504_085332"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_070_to_079/s079_m214"
  ;;
  "s080_m174")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_080_to_089/s080_m174_align_no35_horiz_avgshd_ic___20240504_085335"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s080_m174"
  ;;
  "s081_m049")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_080_to_089/s081_m049_align_no35_horiz_avgshd_ic___20240504_085338"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s081_m049"
  ;;
  "s082_m190")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_080_to_089/s082_m190_align_no35_horiz_avgshd_ic___20240504_085340"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s082_m190"
  ;;
  "s083_m029")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_080_to_089/s083_m029_align_no35_horiz_avgshd_ic___20240504_085343"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s083_m029"
  ;;
  "s084_m069")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_080_to_089/s084_m069_align_no35_horiz_avgshd_ic___20240504_085346"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s084_m069"
  ;;
  "s085_m031")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_080_to_089/s085_m031_align_no35_horiz_avgshd_ic___20240504_085349"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s085_m031"
  ;;
  "s086_m181")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_080_to_089/s086_m181_align_no35_horiz_avgshd_ic___20240504_085352"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s086_m181"
  ;;
  "s087_m155")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_080_to_089/s087_m155_align_no35_horiz_avgshd_ic___20240504_085355"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s087_m155"
  ;;
  "s088_m291")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_080_to_089/s088_m291_align_no35_horiz_avgshd_ic___20240504_085357"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s088_m291"
  ;;
  "s089_m045")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_080_to_089/s089_m045_align_no35_horiz_avgshd_ic___20240504_085400"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_080_to_089/s089_m045"
  ;;
  "s090_m114")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_090_to_099/s090_m114_align_no35_horiz_avgshd_ic___20240504_085403"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s090_m114"
  ;;
  "s091_m246")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_090_to_099/s091_m246_align_no35_horiz_avgshd_ic___20240504_085406"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s091_m246"
  ;;
  "s092_m189")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_090_to_099/s092_m189_align_no35_horiz_avgshd_ic___20240504_085409"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s092_m189"
  ;;
  "s093_m228")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_090_to_099/s093_m228_align_no35_horiz_avgshd_ic___20240504_085411"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s093_m228"
  ;;
  "s094_m059")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_090_to_099/s094_m059_align_no35_horiz_avgshd_ic___20240504_085414"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s094_m059"
  ;;
  "s095_m221")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_090_to_099/s095_m221_align_no35_horiz_avgshd_ic___20240504_085417"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s095_m221"
  ;;
  "s096_m132")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_090_to_099/s096_m132_align_no35_horiz_avgshd_ic___20240504_085420"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s096_m132"
  ;;
  "s097_m149")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_090_to_099/s097_m149_align_no35_horiz_avgshd_ic___20240504_085423"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s097_m149"
  ;;
  "s098_m154")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_090_to_099/s098_m154_align_no35_horiz_avgshd_ic___20240504_085426"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s098_m154"
  ;;
  "s099_m233")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_090_to_099/s099_m233_align_no35_horiz_avgshd_ic___20240504_085428"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_090_to_099/s099_m233"
  ;;
  "s100_m164")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_100_to_109/s100_m164_align_no35_horiz_avgshd_ic___20240504_085431"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s100_m164"
  ;;
  "s101_m313")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_100_to_109/s101_m313_align_no35_horiz_avgshd_ic___20240504_085434"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s101_m313"
  ;;
  "s102_m240")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_100_to_109/s102_m240_align_no35_horiz_avgshd_ic___20240504_085437"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s102_m240"
  ;;
  "s103_m236")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_100_to_109/s103_m236_align_no35_horiz_avgshd_ic___20240504_085440"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s103_m236"
  ;;
  "s104_m323")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_100_to_109/s104_m323_align_no35_horiz_avgshd_ic___20240504_085442"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s104_m323"
  ;;
  "s105_m397")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_100_to_109/s105_m397_align_no35_horiz_avgshd_ic___20240504_085445"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s105_m397"
  ;;
  "s106_m180")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_100_to_109/s106_m180_align_no35_horiz_avgshd_ic___20240504_085448"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s106_m180"
  ;;
  "s107_m192")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_100_to_109/s107_m192_align_no35_horiz_avgshd_ic___20240504_085451"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s107_m192"
  ;;
  "s108_m157")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_100_to_109/s108_m157_align_no35_horiz_avgshd_ic___20240504_085454"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s108_m157"
  ;;
  "s109_m351")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_100_to_109/s109_m351_align_no35_horiz_avgshd_ic___20240504_085456"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_100_to_109/s109_m351"
  ;;
  "s110_m141")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_110_to_119/s110_m141_align_no35_horiz_avgshd_ic___20240504_085459"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s110_m141"
  ;;
  "s111_m117")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_110_to_119/s111_m117_align_no35_horiz_avgshd_ic___20240504_085502"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s111_m117"
  ;;
  "s112_m213")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_110_to_119/s112_m213_align_no35_horiz_avgshd_ic___20240504_085505"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s112_m213"
  ;;
  "s113_m293")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_110_to_119/s113_m293_align_no35_horiz_avgshd_ic___20240504_085508"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s113_m293"
  ;;
  "s114_m094")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_110_to_119/s114_m094_align_no35_horiz_avgshd_ic___20240504_085510"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s114_m094"
  ;;
  "s115_m242")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_110_to_119/s115_m242_align_no35_horiz_avgshd_ic___20240504_085513"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s115_m242"
  ;;
  "s116_m341")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_110_to_119/s116_m341_align_no35_horiz_avgshd_ic___20240504_085516"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s116_m341"
  ;;
  "s117_m023")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_110_to_119/s117_m023_align_no35_horiz_avgshd_ic___20240504_085519"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s117_m023"
  ;;
  "s118_m092")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_110_to_119/s118_m092_align_no35_horiz_avgshd_ic___20240504_085522"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s118_m092"
  ;;
  "s119_m169")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_110_to_119/s119_m169_align_no35_horiz_avgshd_ic___20240504_085525"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_110_to_119/s119_m169"
  ;;
  "s120_m324")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_120_to_129/s120_m324_align_no35_horiz_avgshd_ic___20240504_085527"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s120_m324"
  ;;
  "s121_m217")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_120_to_129/s121_m217_align_no35_horiz_avgshd_ic___20240504_085530"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s121_m217"
  ;;
  "s122_m325")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_120_to_129/s122_m325_align_no35_horiz_avgshd_ic___20240504_085533"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s122_m325"
  ;;
  "s123_m357")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_120_to_129/s123_m357_align_no35_horiz_avgshd_ic___20240504_085536"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s123_m357"
  ;;
  "s124_m129")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_120_to_129/s124_m129_align_no35_horiz_avgshd_ic___20240504_085752"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s124_m129"
  ;;
  "s125_m336")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_120_to_129/s125_m336_align_no35_horiz_avgshd_ic___20240504_090017"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s125_m336"
  ;;
  "s126_m013")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_120_to_129/s126_m013_align_no35_horiz_avgshd_ic___20240504_090447"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s126_m013"
  ;;
  "s127_m232")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_120_to_129/s127_m232_align_no35_horiz_avgshd_ic___20240504_090450"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s127_m232"
  ;;
  "s128_m282")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_120_to_129/s128_m282_align_no35_horiz_avgshd_ic___20240504_090453"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s128_m282"
  ;;
  "s129_m318")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_120_to_129/s129_m318_align_no35_horiz_avgshd_ic___20240504_090456"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_120_to_129/s129_m318"
  ;;
  "s130_m091")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_130_to_139/s130_m091_align_no35_horiz_avgshd_ic___20240504_090459"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_130_to_139/s130_m091"
  ;;
  "s131_m043")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_130_to_139/s131_m043_align_no35_horiz_avgshd_ic___20240504_090501"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_130_to_139/s131_m043"
  ;;
  "s132_m140")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_130_to_139/s132_m140_align_no35_horiz_avgshd_ic___20240504_090504"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_130_to_139/s132_m140"
  ;;
  "s133_m305")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_130_to_139/s133_m305_align_no35_horiz_avgshd_ic___20240504_090507"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_130_to_139/s133_m305"
  ;;
  "s134_m064")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_130_to_139/s134_m064_align_no35_horiz_avgshd_ic___20240504_090510"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_130_to_139/s134_m064"
  ;;
  "s135_m078")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_130_to_139/s135_m078_align_no35_horiz_avgshd_ic___20240504_090513"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_130_to_139/s135_m078"
  ;;
  "s136_m115")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_130_to_139/s136_m115_align_no35_horiz_avgshd_ic___20240504_090515"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_130_to_139/s136_m115"
  ;;
  "s137_m388")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_130_to_139/s137_m388_align_no35_horiz_avgshd_ic___20240504_090518"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_130_to_139/s137_m388"
  ;;
  "s138_m290")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_130_to_139/s138_m290_align_no35_horiz_avgshd_ic___20240504_090521"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_130_to_139/s138_m290"
  ;;
  "s139_m111")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_130_to_139/s139_m111_align_no35_horiz_avgshd_ic___20240504_090524"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_130_to_139/s139_m111"
  ;;
  "s140_m067")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_140_to_149/s140_m067_align_no35_horiz_avgshd_ic___20240504_090527"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_140_to_149/s140_m067"
  ;;
  "s141_m238")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_140_to_149/s141_m238_align_no35_horiz_avgshd_ic___20240504_090529"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_140_to_149/s141_m238"
  ;;
  "s142_m018")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_140_to_149/s142_m018_align_no35_horiz_avgshd_ic___20240504_090532"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_140_to_149/s142_m018"
  ;;
  "s143_m366")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_140_to_149/s143_m366_align_no35_horiz_avgshd_ic___20240504_090535"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_140_to_149/s143_m366"
  ;;
  "s144_m321")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_140_to_149/s144_m321_align_no35_horiz_avgshd_ic___20240504_090538"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_140_to_149/s144_m321"
  ;;
  "s145_m080")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_140_to_149/s145_m080_align_no35_horiz_avgshd_ic___20240504_090541"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_140_to_149/s145_m080"
  ;;
  "s146_m009")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_140_to_149/s146_m009_align_no35_horiz_avgshd_ic___20240504_090543"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_140_to_149/s146_m009"
  ;;
  "s147_m375")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_140_to_149/s147_m375_align_no35_horiz_avgshd_ic___20240504_090546"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_140_to_149/s147_m375"
  ;;
  "s148_m109")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_140_to_149/s148_m109_align_no35_horiz_avgshd_ic___20240504_090549"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_140_to_149/s148_m109"
  ;;
  "s149_m243")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_140_to_149/s149_m243_align_no35_horiz_avgshd_ic___20240504_090552"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_140_to_149/s149_m243"
  ;;
  "s150_m280")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_150_to_159/s150_m280_align_no35_horiz_avgshd_ic___20240504_090555"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_150_to_159/s150_m280"
  ;;
  "s151_m017")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_150_to_159/s151_m017_align_no35_horiz_avgshd_ic___20240504_090557"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_150_to_159/s151_m017"
  ;;
  "s152_m145")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_150_to_159/s152_m145_align_no35_horiz_avgshd_ic___20240504_090600"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_150_to_159/s152_m145"
  ;;
  "s153_m205")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_150_to_159/s153_m205_align_no35_horiz_avgshd_ic___20240504_090603"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_150_to_159/s153_m205"
  ;;
  "s154_m124")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_150_to_159/s154_m124_align_no35_horiz_avgshd_ic___20240504_090606"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_150_to_159/s154_m124"
  ;;
  "s155_m096")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_150_to_159/s155_m096_align_no35_horiz_avgshd_ic___20240504_090609"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_150_to_159/s155_m096"
  ;;
  "s156_m198")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_150_to_159/s156_m198_align_no35_horiz_avgshd_ic___20240504_090611"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_150_to_159/s156_m198"
  ;;
  "s157_m026")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_150_to_159/s157_m026_align_no35_horiz_avgshd_ic___20240504_090614"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_150_to_159/s157_m026"
  ;;
  "s158_m177")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_150_to_159/s158_m177_align_no35_horiz_avgshd_ic___20240504_090617"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_150_to_159/s158_m177"
  ;;
  "s159_m365")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_150_to_159/s159_m365_align_no35_horiz_avgshd_ic___20240504_090620"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_150_to_159/s159_m365"
  ;;
  "s160_m215")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_160_to_169/s160_m215_align_no35_horiz_avgshd_ic___20240504_090623"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_160_to_169/s160_m215"
  ;;
  "s161_m380")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_160_to_169/s161_m380_align_no35_horiz_avgshd_ic___20240504_090625"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_160_to_169/s161_m380"
  ;;
  "s162_m250")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_160_to_169/s162_m250_align_no35_horiz_avgshd_ic___20240504_090628"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_160_to_169/s162_m250"
  ;;
  "s163_m063")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_160_to_169/s163_m063_align_no35_horiz_avgshd_ic___20240504_090631"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_160_to_169/s163_m063"
  ;;
  "s164_m319")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_160_to_169/s164_m319_align_no35_horiz_avgshd_ic___20240504_090634"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_160_to_169/s164_m319"
  ;;
  "s165_m058")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_160_to_169/s165_m058_align_no35_horiz_avgshd_ic___20240504_090637"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_160_to_169/s165_m058"
  ;;
  "s166_m020")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_160_to_169/s166_m020_align_no35_horiz_avgshd_ic___20240504_090639"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_160_to_169/s166_m020"
  ;;
  "s167_m121")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_160_to_169/s167_m121_align_no35_horiz_avgshd_ic___20240504_090642"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_160_to_169/s167_m121"
  ;;
  "s168_m076")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_160_to_169/s168_m076_align_no35_horiz_avgshd_ic___20240504_090645"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_160_to_169/s168_m076"
  ;;
  "s169_m208")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_160_to_169/s169_m208_align_no35_horiz_avgshd_ic___20240504_090648"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_160_to_169/s169_m208"
  ;;
  "s170_m225")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_170_to_179/s170_m225_align_no35_horiz_avgshd_ic___20240504_090651"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_170_to_179/s170_m225"
  ;;
  "s171_m260")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_170_to_179/s171_m260_align_no35_horiz_avgshd_ic___20240504_090653"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_170_to_179/s171_m260"
  ;;
  "s172_m196")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_170_to_179/s172_m196_align_no35_horiz_avgshd_ic___20240504_090656"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_170_to_179/s172_m196"
  ;;
  "s173_m166")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_170_to_179/s173_m166_align_no35_horiz_avgshd_ic___20240504_090659"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_170_to_179/s173_m166"
  ;;
  "s174_m134")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_170_to_179/s174_m134_align_no35_horiz_avgshd_ic___20240504_090702"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_170_to_179/s174_m134"
  ;;
  "s175_m194")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_170_to_179/s175_m194_align_no35_horiz_avgshd_ic___20240504_090705"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_170_to_179/s175_m194"
  ;;
  "s176_m041")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_170_to_179/s176_m041_align_no35_horiz_avgshd_ic___20240504_090707"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_170_to_179/s176_m041"
  ;;
  "s177_m146")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_170_to_179/s177_m146_align_no35_horiz_avgshd_ic___20240504_090710"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_170_to_179/s177_m146"
  ;;
  "s178_m137")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_170_to_179/s178_m137_align_no35_horiz_avgshd_ic___20240504_090713"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_170_to_179/s178_m137"
  ;;
  "s179_m036")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_170_to_179/s179_m036_align_no35_horiz_avgshd_ic___20240504_090716"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_170_to_179/s179_m036"
  ;;
  "s180_m147")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_180_to_189/s180_m147_align_no35_horiz_avgshd_ic___20240504_090719"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_180_to_189/s180_m147"
  ;;
  "s181_m211")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_180_to_189/s181_m211_align_no35_horiz_avgshd_ic___20240504_090721"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_180_to_189/s181_m211"
  ;;
  "s182_m010")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_180_to_189/s182_m010_align_no35_horiz_avgshd_ic___20240504_090724"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_180_to_189/s182_m010"
  ;;
  "s183_m264")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_180_to_189/s183_m264_align_no35_horiz_avgshd_ic___20240504_090727"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_180_to_189/s183_m264"
  ;;
  "s184_m203")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_180_to_189/s184_m203_align_no35_horiz_avgshd_ic___20240504_090730"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_180_to_189/s184_m203"
  ;;
  "s185_m084")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_180_to_189/s185_m084_align_no35_horiz_avgshd_ic___20240504_090733"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_180_to_189/s185_m084"
  ;;
  "s186_m247")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_180_to_189/s186_m247_align_no35_horiz_avgshd_ic___20240504_090736"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_180_to_189/s186_m247"
  ;;
  "s187_m047")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_180_to_189/s187_m047_align_no35_horiz_avgshd_ic___20240504_090738"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_180_to_189/s187_m047"
  ;;
  "s188_m385")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_180_to_189/s188_m385_align_no35_horiz_avgshd_ic___20240504_090741"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_180_to_189/s188_m385"
  ;;
  "s189_m315")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_180_to_189/s189_m315_align_no35_horiz_avgshd_ic___20240504_090744"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_180_to_189/s189_m315"
  ;;
  "s190_m294")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_190_to_199/s190_m294_align_no35_horiz_avgshd_ic___20240504_090747"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_190_to_199/s190_m294"
  ;;
  "s191_m038")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_190_to_199/s191_m038_align_no35_horiz_avgshd_ic___20240504_090750"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_190_to_199/s191_m038"
  ;;
  "s192_m086")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_190_to_199/s192_m086_align_no35_horiz_avgshd_ic___20240504_090752"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_190_to_199/s192_m086"
  ;;
  "s193_m030")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_190_to_199/s193_m030_align_no35_horiz_avgshd_ic___20240504_090755"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_190_to_199/s193_m030"
  ;;
  "s194_m182")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_190_to_199/s194_m182_align_no35_horiz_avgshd_ic___20240504_090758"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_190_to_199/s194_m182"
  ;;
  "s195_m128")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_190_to_199/s195_m128_align_no35_horiz_avgshd_ic___20240504_090801"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_190_to_199/s195_m128"
  ;;
  "s196_m120")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_190_to_199/s196_m120_align_no35_horiz_avgshd_ic___20240504_090804"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_190_to_199/s196_m120"
  ;;
  "s197_m347")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_190_to_199/s197_m347_align_no35_horiz_avgshd_ic___20240504_090806"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_190_to_199/s197_m347"
  ;;
  "s198_m306")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_190_to_199/s198_m306_align_no35_horiz_avgshd_ic___20240504_090809"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_190_to_199/s198_m306"
  ;;
  "s199_m130")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_190_to_199/s199_m130_align_no35_horiz_avgshd_ic___20240504_090812"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_190_to_199/s199_m130"
  ;;
  "s200_m207")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_200_to_209/s200_m207_align_no35_horiz_avgshd_ic___20240504_090815"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_200_to_209/s200_m207"
  ;;
  "s201_m056")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_200_to_209/s201_m056_align_no35_horiz_avgshd_ic___20240504_090818"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_200_to_209/s201_m056"
  ;;
  "s202_m158")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_200_to_209/s202_m158_align_no35_horiz_avgshd_ic___20240504_090820"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_200_to_209/s202_m158"
  ;;
  "s203_m269")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_200_to_209/s203_m269_align_no35_horiz_avgshd_ic___20240504_090823"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_200_to_209/s203_m269"
  ;;
  "s204_m237")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_200_to_209/s204_m237_align_no35_horiz_avgshd_ic___20240504_090826"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_200_to_209/s204_m237"
  ;;
  "s205_m015")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_200_to_209/s205_m015_align_no35_horiz_avgshd_ic___20240504_090829"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_200_to_209/s205_m015"
  ;;
  "s206_m283")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_200_to_209/s206_m283_align_no35_horiz_avgshd_ic___20240504_090832"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_200_to_209/s206_m283"
  ;;
  "s207_m263")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_200_to_209/s207_m263_align_no35_horiz_avgshd_ic___20240504_090835"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_200_to_209/s207_m263"
  ;;
  "s208_m254")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_200_to_209/s208_m254_align_no35_horiz_avgshd_ic___20240504_090837"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_200_to_209/s208_m254"
  ;;
  "s209_m249")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_200_to_209/s209_m249_align_no35_horiz_avgshd_ic___20240504_090840"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_200_to_209/s209_m249"
  ;;
  "s210_m062")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_210_to_219/s210_m062_align_no35_horiz_avgshd_ic___20240504_090843"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_210_to_219/s210_m062"
  ;;
  "s211_m350")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_210_to_219/s211_m350_align_no35_horiz_avgshd_ic___20240504_090846"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_210_to_219/s211_m350"
  ;;
  "s212_m170")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_210_to_219/s212_m170_align_no35_horiz_avgshd_ic___20240504_090849"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_210_to_219/s212_m170"
  ;;
  "s213_m386")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_210_to_219/s213_m386_align_no35_horiz_avgshd_ic___20240504_090851"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_210_to_219/s213_m386"
  ;;
  "s214_m095")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_210_to_219/s214_m095_align_no35_horiz_avgshd_ic___20240504_090854"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_210_to_219/s214_m095"
  ;;
  "s215_m222")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_210_to_219/s215_m222_align_no35_horiz_avgshd_ic___20240504_090857"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_210_to_219/s215_m222"
  ;;
  "s216_m271")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_210_to_219/s216_m271_align_no35_horiz_avgshd_ic___20240504_090900"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_210_to_219/s216_m271"
  ;;
  "s217_m392")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_210_to_219/s217_m392_align_no35_horiz_avgshd_ic___20240504_090903"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_210_to_219/s217_m392"
  ;;
  "s218_m142")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_210_to_219/s218_m142_align_no35_horiz_avgshd_ic___20240504_090906"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_210_to_219/s218_m142"
  ;;
  "s219_m199")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_210_to_219/s219_m199_align_no35_horiz_avgshd_ic___20240504_090908"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_210_to_219/s219_m199"
  ;;
  "s220_m224")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_220_to_229/s220_m224_align_no35_horiz_avgshd_ic___20240504_090911"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_220_to_229/s220_m224"
  ;;
  "s221_m176")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_220_to_229/s221_m176_align_no35_horiz_avgshd_ic___20240504_090914"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_220_to_229/s221_m176"
  ;;
  "s222_m309")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_220_to_229/s222_m309_align_no35_horiz_avgshd_ic___20240504_090917"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_220_to_229/s222_m309"
  ;;
  "s223_m329")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_220_to_229/s223_m329_align_no35_horiz_avgshd_ic___20240504_090920"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_220_to_229/s223_m329"
  ;;
  "s224_m334")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_220_to_229/s224_m334_align_no35_horiz_avgshd_ic___20240504_090922"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_220_to_229/s224_m334"
  ;;
  "s225_m358")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_220_to_229/s225_m358_align_no35_horiz_avgshd_ic___20240504_090925"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_220_to_229/s225_m358"
  ;;
  "s226_m219")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_220_to_229/s226_m219_align_no35_horiz_avgshd_ic___20240504_090928"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_220_to_229/s226_m219"
  ;;
  "s227_m396")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_220_to_229/s227_m396_align_no35_horiz_avgshd_ic___20240504_090931"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_220_to_229/s227_m396"
  ;;
  "s228_m363")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_220_to_229/s228_m363_align_no35_horiz_avgshd_ic___20240504_090934"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_220_to_229/s228_m363"
  ;;
  "s229_m075")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_220_to_229/s229_m075_align_no35_horiz_avgshd_ic___20240504_090936"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_220_to_229/s229_m075"
  ;;
  "s230_m126")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_230_to_239/s230_m126_align_no35_horiz_avgshd_ic___20240504_090939"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_230_to_239/s230_m126"
  ;;
  "s231_m304")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_230_to_239/s231_m304_align_no35_horiz_avgshd_ic___20240504_090942"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_230_to_239/s231_m304"
  ;;
  "s232_m314")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_230_to_239/s232_m314_align_no35_horiz_avgshd_ic___20240504_090945"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_230_to_239/s232_m314"
  ;;
  "s233_m364")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_230_to_239/s233_m364_align_no35_horiz_avgshd_ic___20240504_090948"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_230_to_239/s233_m364"
  ;;
  "s234_m289")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_230_to_239/s234_m289_align_no35_horiz_avgshd_ic___20240504_090950"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_230_to_239/s234_m289"
  ;;
  "s235_m226")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_230_to_239/s235_m226_align_no35_horiz_avgshd_ic___20240504_090953"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_230_to_239/s235_m226"
  ;;
  "s236_m195")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_230_to_239/s236_m195_align_no35_horiz_avgshd_ic___20240504_090956"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_230_to_239/s236_m195"
  ;;
  "s237_m267")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_230_to_239/s237_m267_align_no35_horiz_avgshd_ic___20240504_090959"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_230_to_239/s237_m267"
  ;;
  "s238_m266")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_230_to_239/s238_m266_align_no35_horiz_avgshd_ic___20240504_091002"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_230_to_239/s238_m266"
  ;;
  "s239_m320")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_230_to_239/s239_m320_align_no35_horiz_avgshd_ic___20240504_091005"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_230_to_239/s239_m320"
  ;;
  "s240_m001")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_240_to_249/s240_m001_align_no35_horiz_avgshd_ic___20240504_091007"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_240_to_249/s240_m001"
  ;;
  "s241_m112")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_240_to_249/s241_m112_align_no35_horiz_avgshd_ic___20240504_091010"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_240_to_249/s241_m112"
  ;;
  "s242_m040")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_240_to_249/s242_m040_align_no35_horiz_avgshd_ic___20240504_091013"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_240_to_249/s242_m040"
  ;;
  "s243_m274")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_240_to_249/s243_m274_align_no35_horiz_avgshd_ic___20240504_091016"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_240_to_249/s243_m274"
  ;;
  "s244_m116")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_240_to_249/s244_m116_align_no35_horiz_avgshd_ic___20240504_091019"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_240_to_249/s244_m116"
  ;;
  "s245_m071")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_240_to_249/s245_m071_align_no35_horiz_avgshd_ic___20240504_091021"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_240_to_249/s245_m071"
  ;;
  "s246_m052")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_240_to_249/s246_m052_align_no35_horiz_avgshd_ic___20240504_091024"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_240_to_249/s246_m052"
  ;;
  "s247_m299")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_240_to_249/s247_m299_align_no35_horiz_avgshd_ic___20240504_091027"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_240_to_249/s247_m299"
  ;;
  "s248_m012")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_240_to_249/s248_m012_align_no35_horiz_avgshd_ic___20240504_091030"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_240_to_249/s248_m012"
  ;;
  "s249_m391")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_240_to_249/s249_m391_align_no35_horiz_avgshd_ic___20240504_091033"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_240_to_249/s249_m391"
  ;;
  "s250_m082")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_250_to_259/s250_m082_align_no35_horiz_avgshd_ic___20240504_091035"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_250_to_259/s250_m082"
  ;;
  "s251_m108")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_250_to_259/s251_m108_align_no35_horiz_avgshd_ic___20240504_091038"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_250_to_259/s251_m108"
  ;;
  "s252_m028")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_250_to_259/s252_m028_align_no35_horiz_avgshd_ic___20240504_091041"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_250_to_259/s252_m028"
  ;;
  "s253_m100")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_250_to_259/s253_m100_align_no35_horiz_avgshd_ic___20240504_091044"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_250_to_259/s253_m100"
  ;;
  "s254_m337")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_250_to_259/s254_m337_align_no35_horiz_avgshd_ic___20240504_091047"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_250_to_259/s254_m337"
  ;;
  "s255_m103")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_250_to_259/s255_m103_align_no35_horiz_avgshd_ic___20240504_091049"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_250_to_259/s255_m103"
  ;;
  "s256_m060")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_250_to_259/s256_m060_align_no35_horiz_avgshd_ic___20240504_091052"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_250_to_259/s256_m060"
  ;;
  "s257_m369")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_250_to_259/s257_m369_align_no35_horiz_avgshd_ic___20240504_091055"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_250_to_259/s257_m369"
  ;;
  "s258_m223")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_250_to_259/s258_m223_align_no35_horiz_avgshd_ic___20240504_091058"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_250_to_259/s258_m223"
  ;;
  "s259_m230")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_250_to_259/s259_m230_align_no35_horiz_avgshd_ic___20240504_091101"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_250_to_259/s259_m230"
  ;;
  "s260_m136")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_260_to_269/s260_m136_align_no35_horiz_avgshd_ic___20240504_091103"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_260_to_269/s260_m136"
  ;;
  "s261_m000")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_260_to_269/s261_m000_align_no35_horiz_avgshd_ic___20240504_091106"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_260_to_269/s261_m000"
  ;;
  "s262_m066")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_260_to_269/s262_m066_align_no35_horiz_avgshd_ic___20240504_091109"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_260_to_269/s262_m066"
  ;;
  "s263_m186")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_260_to_269/s263_m186_align_no35_horiz_avgshd_ic___20240504_091112"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_260_to_269/s263_m186"
  ;;
  "s264_m335")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_260_to_269/s264_m335_align_no35_horiz_avgshd_ic___20240504_091115"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_260_to_269/s264_m335"
  ;;
  "s265_m090")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_260_to_269/s265_m090_align_no35_horiz_avgshd_ic___20240504_091117"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_260_to_269/s265_m090"
  ;;
  "s266_m127")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_260_to_269/s266_m127_align_no35_horiz_avgshd_ic___20240504_091120"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_260_to_269/s266_m127"
  ;;
  "s267_m308")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_260_to_269/s267_m308_align_no35_horiz_avgshd_ic___20240504_091123"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_260_to_269/s267_m308"
  ;;
  "s268_m317")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_260_to_269/s268_m317_align_no35_horiz_avgshd_ic___20240504_091126"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_260_to_269/s268_m317"
  ;;
  "s269_m046")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_260_to_269/s269_m046_align_no35_horiz_avgshd_ic___20240504_091129"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_260_to_269/s269_m046"
  ;;
  "s270_m024")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_270_to_279/s270_m024_align_no35_horiz_avgshd_ic___20240504_091131"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_270_to_279/s270_m024"
  ;;
  "s271_m301")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_270_to_279/s271_m301_align_no35_horiz_avgshd_ic___20240504_091134"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_270_to_279/s271_m301"
  ;;
  "s272_m053")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_270_to_279/s272_m053_align_no35_horiz_avgshd_ic___20240504_091137"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_270_to_279/s272_m053"
  ;;
  "s273_m019")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_270_to_279/s273_m019_align_no35_horiz_avgshd_ic___20240504_091140"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_270_to_279/s273_m019"
  ;;
  "s274_m165")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_270_to_279/s274_m165_align_no35_horiz_avgshd_ic___20240504_091143"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_270_to_279/s274_m165"
  ;;
  "s275_m345")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_270_to_279/s275_m345_align_no35_horiz_avgshd_ic___20240504_091145"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_270_to_279/s275_m345"
  ;;
  "s276_m204")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_270_to_279/s276_m204_align_no35_horiz_avgshd_ic___20240504_091148"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_270_to_279/s276_m204"
  ;;
  "s277_m272")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_270_to_279/s277_m272_align_no35_horiz_avgshd_ic___20240504_091151"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_270_to_279/s277_m272"
  ;;
  "s278_m193")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_270_to_279/s278_m193_align_no35_horiz_avgshd_ic___20240504_091154"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_270_to_279/s278_m193"
  ;;
  "s279_m161")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_270_to_279/s279_m161_align_no35_horiz_avgshd_ic___20240504_091157"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_270_to_279/s279_m161"
  ;;
  "s280_m256")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_280_to_289/s280_m256_align_no35_horiz_avgshd_ic___20240504_091159"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_280_to_289/s280_m256"
  ;;
  "s281_m206")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_280_to_289/s281_m206_align_no35_horiz_avgshd_ic___20240504_091202"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_280_to_289/s281_m206"
  ;;
  "s282_m220")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_280_to_289/s282_m220_align_no35_horiz_avgshd_ic___20240504_091205"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_280_to_289/s282_m220"
  ;;
  "s283_m106")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_280_to_289/s283_m106_align_no35_horiz_avgshd_ic___20240504_091208"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_280_to_289/s283_m106"
  ;;
  "s284_m050")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_280_to_289/s284_m050_align_no35_horiz_avgshd_ic___20240504_091211"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_280_to_289/s284_m050"
  ;;
  "s285_m201")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_280_to_289/s285_m201_align_no35_horiz_avgshd_ic___20240504_091214"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_280_to_289/s285_m201"
  ;;
  "s286_m179")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_280_to_289/s286_m179_align_no35_horiz_avgshd_ic___20240504_091216"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_280_to_289/s286_m179"
  ;;
  "s287_m359")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_280_to_289/s287_m359_align_no35_horiz_avgshd_ic___20240504_091219"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_280_to_289/s287_m359"
  ;;
  "s288_m276")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_280_to_289/s288_m276_align_no35_horiz_avgshd_ic___20240504_091222"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_280_to_289/s288_m276"
  ;;
  "s289_m014")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_280_to_289/s289_m014_align_no35_horiz_avgshd_ic___20240504_091225"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_280_to_289/s289_m014"
  ;;
  "s290_m144")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_290_to_299/s290_m144_align_no35_horiz_avgshd_ic___20240504_091228"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_290_to_299/s290_m144"
  ;;
  "s291_m262")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_290_to_299/s291_m262_align_no35_horiz_avgshd_ic___20240504_091230"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_290_to_299/s291_m262"
  ;;
  "s292_m065")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_290_to_299/s292_m065_align_no35_horiz_avgshd_ic___20240504_091233"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_290_to_299/s292_m065"
  ;;
  "s293_m400")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_290_to_299/s293_m400_align_no35_horiz_avgshd_ic___20240504_091236"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_290_to_299/s293_m400"
  ;;
  "s294_m123")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_290_to_299/s294_m123_align_no35_horiz_avgshd_ic___20240504_091239"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_290_to_299/s294_m123"
  ;;
  "s295_m175")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_290_to_299/s295_m175_align_no35_horiz_avgshd_ic___20240504_091242"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_290_to_299/s295_m175"
  ;;
  "s296_m339")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_290_to_299/s296_m339_align_no35_horiz_avgshd_ic___20240504_091245"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_290_to_299/s296_m339"
  ;;
  "s297_m048")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_290_to_299/s297_m048_align_no35_horiz_avgshd_ic___20240504_091247"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_290_to_299/s297_m048"
  ;;
  "s298_m311")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_290_to_299/s298_m311_align_no35_horiz_avgshd_ic___20240504_091250"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_290_to_299/s298_m311"
  ;;
  "s299_m034")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_290_to_299/s299_m034_align_no35_horiz_avgshd_ic___20240504_091253"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_290_to_299/s299_m034"
  ;;
  "s300_m160")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_300_to_309/s300_m160_align_no35_horiz_avgshd_ic___20240504_091256"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_300_to_309/s300_m160"
  ;;
  "s301_m378")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_300_to_309/s301_m378_align_no35_horiz_avgshd_ic___20240504_091259"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_300_to_309/s301_m378"
  ;;
  "s302_m184")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_300_to_309/s302_m184_align_no35_horiz_avgshd_ic___20240504_091301"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_300_to_309/s302_m184"
  ;;
  "s303_m083")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_300_to_309/s303_m083_align_no35_horiz_avgshd_ic___20240504_091304"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_300_to_309/s303_m083"
  ;;
  "s304_m370")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_300_to_309/s304_m370_align_no35_horiz_avgshd_ic___20240504_091307"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_300_to_309/s304_m370"
  ;;
  "s305_m035")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_300_to_309/s305_m035_align_no35_horiz_avgshd_ic___20240504_091310"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_300_to_309/s305_m035"
  ;;
  "s306_m340")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_300_to_309/s306_m340_align_no35_horiz_avgshd_ic___20240504_091313"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_300_to_309/s306_m340"
  ;;
  "s307_m006")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_300_to_309/s307_m006_align_no35_horiz_avgshd_ic___20240504_091315"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_300_to_309/s307_m006"
  ;;
  "s308_m098")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_300_to_309/s308_m098_align_no35_horiz_avgshd_ic___20240504_091318"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_300_to_309/s308_m098"
  ;;
  "s309_m110")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_300_to_309/s309_m110_align_no35_horiz_avgshd_ic___20240504_091321"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_300_to_309/s309_m110"
  ;;
  "s310_m368")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_310_to_319/s310_m368_align_no35_horiz_avgshd_ic___20240504_091324"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_310_to_319/s310_m368"
  ;;
  "s311_m297")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_310_to_319/s311_m297_align_no35_horiz_avgshd_ic___20240504_091327"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_310_to_319/s311_m297"
  ;;
  "s312_m171")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_310_to_319/s312_m171_align_no35_horiz_avgshd_ic___20240504_091329"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_310_to_319/s312_m171"
  ;;
  "s313_m298")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_310_to_319/s313_m298_align_no35_horiz_avgshd_ic___20240504_091332"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_310_to_319/s313_m298"
  ;;
  "s314_m338")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_310_to_319/s314_m338_align_no35_horiz_avgshd_ic___20240504_091335"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_310_to_319/s314_m338"
  ;;
  "s315_m303")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_310_to_319/s315_m303_align_no35_horiz_avgshd_ic___20240504_091338"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_310_to_319/s315_m303"
  ;;
  "s316_m068")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_310_to_319/s316_m068_align_no35_horiz_avgshd_ic___20240504_091341"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_310_to_319/s316_m068"
  ;;
  "s317_m361")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_310_to_319/s317_m361_align_no35_horiz_avgshd_ic___20240504_142620"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_310_to_319/s317_m361"
  ;;
  "s318_m389")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_310_to_319/s318_m389_align_no35_horiz_avgshd_ic___20240504_091347"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_310_to_319/s318_m389"
  ;;
  "s319_m002")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_310_to_319/s319_m002_align_no35_horiz_avgshd_ic___20240504_091349"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_310_to_319/s319_m002"
  ;;
  "s320_m021")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_320_to_329/s320_m021_align_no35_horiz_avgshd_ic___20240504_091352"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_320_to_329/s320_m021"
  ;;
  "s321_m101")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_320_to_329/s321_m101_align_no35_horiz_avgshd_ic___20240504_091355"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_320_to_329/s321_m101"
  ;;
  "s322_m005")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_320_to_329/s322_m005_align_no35_horiz_avgshd_ic___20240504_091358"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_320_to_329/s322_m005"
  ;;
  "s323_m354")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_320_to_329/s323_m354_align_no35_horiz_avgshd_ic___20240504_091401"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_320_to_329/s323_m354"
  ;;
  "s324_m156")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_320_to_329/s324_m156_align_no35_horiz_avgshd_ic___20240504_091403"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_320_to_329/s324_m156"
  ;;
  "s325_m245")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_320_to_329/s325_m245_align_no35_horiz_avgshd_ic___20240504_091406"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_320_to_329/s325_m245"
  ;;
  "s326_m200")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_320_to_329/s326_m200_align_no35_horiz_avgshd_ic___20240504_091409"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_320_to_329/s326_m200"
  ;;
  "s327_m244")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_320_to_329/s327_m244_align_no35_horiz_avgshd_ic___20240504_142624"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_320_to_329/s327_m244"
  ;;
  "s328_m135")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_320_to_329/s328_m135_align_no35_horiz_avgshd_ic___20240504_142627"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_320_to_329/s328_m135"
  ;;
  "s329_m401")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_320_to_329/s329_m401_align_no35_horiz_avgshd_ic___20240504_091417"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_320_to_329/s329_m401"
  ;;
  "s330_m085")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_330_to_339/s330_m085_align_no35_horiz_avgshd_ic___20240504_091420"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_330_to_339/s330_m085"
  ;;
  "s331_m251")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_330_to_339/s331_m251_align_no35_horiz_avgshd_ic___20240504_142630"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_330_to_339/s331_m251"
  ;;
  "s332_m027")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_330_to_339/s332_m027_align_no35_horiz_avgshd_ic___20240504_091426"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_330_to_339/s332_m027"
  ;;
  "s333_m163")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_330_to_339/s333_m163_align_no35_horiz_avgshd_ic___20240504_091429"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_330_to_339/s333_m163"
  ;;
  "s334_m343")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_330_to_339/s334_m343_align_no35_horiz_avgshd_ic___20240504_091431"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_330_to_339/s334_m343"
  ;;
  "s335_m011")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_330_to_339/s335_m011_align_no35_horiz_avgshd_ic___20240504_142632"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_330_to_339/s335_m011"
  ;;
  "s336_m373")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_330_to_339/s336_m373_align_no35_horiz_avgshd_ic___20240504_142635"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_330_to_339/s336_m373"
  ;;
  "s337_m394")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_330_to_339/s337_m394_align_no35_horiz_avgshd_ic___20240504_091440"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_330_to_339/s337_m394"
  ;;
  "s338_m332")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_330_to_339/s338_m332_align_no35_horiz_avgshd_ic___20240504_091443"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_330_to_339/s338_m332"
  ;;
  "s339_m032")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_330_to_339/s339_m032_align_no35_horiz_avgshd_ic___20240504_142638"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_330_to_339/s339_m032"
  ;;
  "s340_m371")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_340_to_349/s340_m371_align_no35_horiz_avgshd_ic___20240504_142641"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_340_to_349/s340_m371"
  ;;
  "s341_m356")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_340_to_349/s341_m356_align_no35_horiz_avgshd_ic___20240504_142644"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_340_to_349/s341_m356"
  ;;
  "s342_m191")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_340_to_349/s342_m191_align_no35_horiz_avgshd_ic___20240504_091454"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_340_to_349/s342_m191"
  ;;
  "s343_m261")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_340_to_349/s343_m261_align_no35_horiz_avgshd_ic___20240504_091457"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_340_to_349/s343_m261"
  ;;
  "s344_m216")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_340_to_349/s344_m216_align_no35_horiz_avgshd_ic___20240504_142646"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_340_to_349/s344_m216"
  ;;
  "s345_m327")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_340_to_349/s345_m327_align_no35_horiz_avgshd_ic___20240504_142649"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_340_to_349/s345_m327"
  ;;
  "s346_m312")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_340_to_349/s346_m312_align_no35_horiz_avgshd_ic___20240504_142652"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_340_to_349/s346_m312"
  ;;
  "s347_m342")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_340_to_349/s347_m342_align_no35_horiz_avgshd_ic___20240504_091508"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_340_to_349/s347_m342"
  ;;
  "s348_m061")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_340_to_349/s348_m061_align_no35_horiz_avgshd_ic___20240504_091511"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_340_to_349/s348_m061"
  ;;
  "s349_m288")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_340_to_349/s349_m288_align_no35_horiz_avgshd_ic___20240504_142655"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_340_to_349/s349_m288"
  ;;
  "s350_m352")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_350_to_359/s350_m352_align_no35_horiz_avgshd_ic___20240504_091516"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_350_to_359/s350_m352"
  ;;
  "s351_m218")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_350_to_359/s351_m218_align_no35_horiz_avgshd_ic___20240504_091519"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_350_to_359/s351_m218"
  ;;
  "s352_m234")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_350_to_359/s352_m234_align_no35_horiz_avgshd_ic___20240504_091522"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_350_to_359/s352_m234"
  ;;
  "s353_m042")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_350_to_359/s353_m042_align_no35_horiz_avgshd_ic___20240504_091525"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_350_to_359/s353_m042"
  ;;
  "s354_m093")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_350_to_359/s354_m093_align_no35_horiz_avgshd_ic___20240504_142657"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_350_to_359/s354_m093"
  ;;
  "s355_m310")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_350_to_359/s355_m310_align_no35_horiz_avgshd_ic___20240504_091530"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_350_to_359/s355_m310"
  ;;
  "s356_m197")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_350_to_359/s356_m197_align_no35_horiz_avgshd_ic___20240504_091533"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_350_to_359/s356_m197"
  ;;
  "s357_m051")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_350_to_359/s357_m051_align_no35_horiz_avgshd_ic___20240504_142700"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_350_to_359/s357_m051"
  ;;
  "s358_m074")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_350_to_359/s358_m074_align_no35_horiz_avgshd_ic___20240504_142703"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_350_to_359/s358_m074"
  ;;
  "s359_m248")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_350_to_359/s359_m248_align_no35_horiz_avgshd_ic___20240504_142706"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_350_to_359/s359_m248"
  ;;
  "s360_m346")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_360_to_369/s360_m346_align_no35_horiz_avgshd_ic___20240504_142708"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_360_to_369/s360_m346"
  ;;
  "s361_m125")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_360_to_369/s361_m125_align_no35_horiz_avgshd_ic___20240504_142711"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_360_to_369/s361_m125"
  ;;
  "s362_m255")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_360_to_369/s362_m255_align_no35_horiz_avgshd_ic___20240504_091550"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_360_to_369/s362_m255"
  ;;
  "s363_m344")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_360_to_369/s363_m344_align_no35_horiz_avgshd_ic___20240504_142714"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_360_to_369/s363_m344"
  ;;
  "s364_m374")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_360_to_369/s364_m374_align_no35_horiz_avgshd_ic___20240504_091555"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_360_to_369/s364_m374"
  ;;
  "s365_m383")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_360_to_369/s365_m383_align_no35_horiz_avgshd_ic___20240504_091558"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_360_to_369/s365_m383"
  ;;
  "s366_m088")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_360_to_369/s366_m088_align_no35_horiz_avgshd_ic___20240504_091601"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_360_to_369/s366_m088"
  ;;
  "s367_m007")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_360_to_369/s367_m007_align_no35_horiz_avgshd_ic___20240504_091604"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_360_to_369/s367_m007"
  ;;
  "s368_m257")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_360_to_369/s368_m257_align_no35_horiz_avgshd_ic___20240504_091607"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_360_to_369/s368_m257"
  ;;
  "s369_m143")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_360_to_369/s369_m143_align_no35_horiz_avgshd_ic___20240504_091609"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_360_to_369/s369_m143"
  ;;
  "s370_m159")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_370_to_379/s370_m159_align_no35_horiz_avgshd_ic___20240504_091612"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_370_to_379/s370_m159"
  ;;
  "s371_m087")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_370_to_379/s371_m087_align_no35_horiz_avgshd_ic___20240504_091615"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_370_to_379/s371_m087"
  ;;
  "s372_m402")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_370_to_379/s372_m402_align_no35_horiz_avgshd_ic___20240504_091618"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_370_to_379/s372_m402"
  ;;
  "s373_m258")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_370_to_379/s373_m258_align_no35_horiz_avgshd_ic___20240504_091621"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_370_to_379/s373_m258"
  ;;
  "s374_m077")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_370_to_379/s374_m077_align_no35_horiz_avgshd_ic___20240504_091624"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_370_to_379/s374_m077"
  ;;
  "s375_m284")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_370_to_379/s375_m284_align_no35_horiz_avgshd_ic___20240504_091626"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_370_to_379/s375_m284"
  ;;
  "s376_m398")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_370_to_379/s376_m398_align_no35_horiz_avgshd_ic___20240504_091629"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_370_to_379/s376_m398"
  ;;
  "s377_m202")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_370_to_379/s377_m202_align_no35_horiz_avgshd_ic___20240504_091632"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_370_to_379/s377_m202"
  ;;
  "s378_m376")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_370_to_379/s378_m376_align_no35_horiz_avgshd_ic___20240504_142717"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_370_to_379/s378_m376"
  ;;
  "s379_m229")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_370_to_379/s379_m229_align_no35_horiz_avgshd_ic___20240504_142720"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_370_to_379/s379_m229"
  ;;
  "s380_m382")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_380_to_389/s380_m382_align_no35_horiz_avgshd_ic___20240504_142722"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_380_to_389/s380_m382"
  ;;
  "s381_m377")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_380_to_389/s381_m377_align_no35_horiz_avgshd_ic___20240504_142725"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_380_to_389/s381_m377"
  ;;
  "s382_m328")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_380_to_389/s382_m328_align_no35_horiz_avgshd_ic___20240504_142728"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_380_to_389/s382_m328"
  ;;
  "s383_m004")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_380_to_389/s383_m004_align_no35_horiz_avgshd_ic___20240504_142731"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_380_to_389/s383_m004"
  ;;
  "s384_m384")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_380_to_389/s384_m384_align_no35_horiz_avgshd_ic___20240504_142734"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_380_to_389/s384_m384"
  ;;
  "s385_m227")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_380_to_389/s385_m227_align_no35_horiz_avgshd_ic___20240504_091655"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_380_to_389/s385_m227"
  ;;
  "s386_m270")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_380_to_389/s386_m270_align_no35_horiz_avgshd_ic___20240504_091657"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_380_to_389/s386_m270"
  ;;
  "s387_m187")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_380_to_389/s387_m187_align_no35_horiz_avgshd_ic___20240504_091700"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_380_to_389/s387_m187"
  ;;
  "s388_m072")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_380_to_389/s388_m072_align_no35_horiz_avgshd_ic___20240504_091703"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_380_to_389/s388_m072"
  ;;
  "s389_m322")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_380_to_389/s389_m322_align_no35_horiz_avgshd_ic___20240504_091706"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_380_to_389/s389_m322"
  ;;
  "s390_m273")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_390_to_399/s390_m273_align_no35_horiz_avgshd_ic___20240504_142736"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_390_to_399/s390_m273"
  ;;
  "s391_m393")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_390_to_399/s391_m393_align_no35_horiz_avgshd_ic___20240504_142739"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_390_to_399/s391_m393"
  ;;
  "s392_m168")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_390_to_399/s392_m168_align_no35_horiz_avgshd_ic___20240504_142742"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_390_to_399/s392_m168"
  ;;
  "s393_m138")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_390_to_399/s393_m138_align_no35_horiz_avgshd_ic___20240504_142745"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_390_to_399/s393_m138"
  ;;
  "s394_m360")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_390_to_399/s394_m360_align_no35_horiz_avgshd_ic___20240504_142747"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_390_to_399/s394_m360"
  ;;
  "s395_m113")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_390_to_399/s395_m113_align_no35_horiz_avgshd_ic___20240504_142750"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_390_to_399/s395_m113"
  ;;
  "s396_m153")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_390_to_399/s396_m153_align_no35_horiz_avgshd_ic___20240504_142753"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_390_to_399/s396_m153"
  ;;
  "s397_m148")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_390_to_399/s397_m148_align_no35_horiz_avgshd_ic___20240504_091728"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_390_to_399/s397_m148"
  ;;
  "s398_m183")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_390_to_399/s398_m183_align_no35_horiz_avgshd_ic___20240504_091731"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_390_to_399/s398_m183"
  ;;
  "s399_m185")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_390_to_399/s399_m185_align_no35_horiz_avgshd_ic___20240504_142756"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_390_to_399/s399_m185"
  ;;
  "s400_m152")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_400_to_402/s400_m152_align_no35_horiz_avgshd_ic___20240504_091736"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_400_to_402/s400_m152"
  ;;
  "s401_m353")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_400_to_402/s401_m353_align_no35_horiz_avgshd_ic___20240504_142759"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_400_to_402/s401_m353"
  ;;
  "s402_m399")
    export N5_ALIGNED_SLAB_DATASET="/render/slab_400_to_402/s402_m399_align_no35_horiz_avgshd_ic___20240504_142801"
    export N5_HEIGHT_FIELDS_FIX_DATASET="/heightfields_fix/slab_400_to_402/s402_m399"
  ;;
esac

# ln -s min heightfields/.../min

# --------------------------------------------------------------------
# Helper Functions
# --------------------------------------------------------------------
validateDirectoriesExist () {
  local DIR
  for DIR in "$@"; do
    if [[ ! -d ${DIR} ]]; then
      echo "ERROR: ${DIR} directory does not exist"
      exit 1
    fi
  done
}

setupRunLog () {
  PREFIX="$1"
  LOG_DIR="${2:-logs}"
  mkdir -p "${LOG_DIR}"
  echo "${LOG_DIR}/${PREFIX}.${RUN_TIMESTAMP}.log"
}

getSlabProjectName () {
  SLAB="$1"
  Z_PREFIX=$(echo "${SLAB}" | cut -c2-3)  # 00 to 40
  FIRST_Z="${Z_PREFIX}0"
  LAST_Z="${Z_PREFIX}9"
  if [[ "${Z_PREFIX}" == "40" ]]; then
    LAST_Z="402"
  fi
  echo "slab_${FIRST_Z}_to_${LAST_Z}"
}
