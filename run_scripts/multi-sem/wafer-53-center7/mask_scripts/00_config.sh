#!/bin/bash

umask 0002

if (( $# != 1 )); then
  echo "USAGE: $0"
  exit 1
fi

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
export N5_FLAT_DATASET_ROOT="/flat/${RAW_SLAB}"
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
exportMaskDatasetNameFor() {
	RAW_SLAB="$1"
	case "${RAW_SLAB}" in
		"s001_m239")
			export N5_MASK_DATASET="/render/slab_000_to_009/s001_m239_align_no35_horiz_avgshd_ic___mask_20240504_144211"
			;;
		"s002_m395")
			export N5_MASK_DATASET="/render/slab_000_to_009/s002_m395_align_no35_horiz_avgshd_ic___mask_20240504_144716"
			;;
		"s003_m348")
			export N5_MASK_DATASET="/render/slab_000_to_009/s003_m348_align_no35_horiz_avgshd_ic___mask_20240504_144719"
			;;
		"s004_m107")
			export N5_MASK_DATASET="/render/slab_000_to_009/s004_m107_align_no35_horiz_avgshd_ic___mask_20240504_144722"
			;;
		"s005_m316")
			export N5_MASK_DATASET="/render/slab_000_to_009/s005_m316_align_no35_horiz_avgshd_ic___mask_20240504_144725"
			;;
		"s006_m167")
			export N5_MASK_DATASET="/render/slab_000_to_009/s006_m167_align_no35_horiz_avgshd_ic___mask_20240504_144727"
			;;
		"s007_m285")
			export N5_MASK_DATASET="/render/slab_000_to_009/s007_m285_align_no35_horiz_avgshd_ic___mask_20240504_144730"
			;;
		"s008_m281")
			export N5_MASK_DATASET="/render/slab_000_to_009/s008_m281_align_no35_horiz_avgshd_ic___mask_20240504_144733"
			;;
		"s009_m172")
			export N5_MASK_DATASET="/render/slab_000_to_009/s009_m172_align_no35_horiz_avgshd_ic___mask_20240504_144736"
			;;
		"s010_m231")
			export N5_MASK_DATASET="/render/slab_010_to_019/s010_m231_align_no35_horiz_avgshd_ic___mask_20240504_144738"
			;;
		"s011_m151")
			export N5_MASK_DATASET="/render/slab_010_to_019/s011_m151_align_no35_horiz_avgshd_ic___mask_20240504_144741"
			;;
		"s012_m097")
			export N5_MASK_DATASET="/render/slab_010_to_019/s012_m097_align_no35_horiz_avgshd_ic___mask_20240504_144744"
			;;
		"s013_m333")
			export N5_MASK_DATASET="/render/slab_010_to_019/s013_m333_align_no35_horiz_avgshd_ic___mask_20240504_144747"
			;;
		"s014_m178")
			export N5_MASK_DATASET="/render/slab_010_to_019/s014_m178_align_no35_horiz_avgshd_ic___mask_20240504_144749"
			;;
		"s015_m278")
			export N5_MASK_DATASET="/render/slab_010_to_019/s015_m278_align_no35_horiz_avgshd_ic___mask_20240504_144752"
			;;
		"s016_m099")
			export N5_MASK_DATASET="/render/slab_010_to_019/s016_m099_align_no35_horiz_avgshd_ic___mask_20240504_144755"
			;;
		"s017_m330")
			export N5_MASK_DATASET="/render/slab_010_to_019/s017_m330_align_no35_horiz_avgshd_ic___mask_20240504_144758"
			;;
		"s018_m300")
			export N5_MASK_DATASET="/render/slab_010_to_019/s018_m300_align_no35_horiz_avgshd_ic___mask_20240504_144800"
			;;
		"s019_m073")
			export N5_MASK_DATASET="/render/slab_010_to_019/s019_m073_align_no35_horiz_avgshd_ic___mask_20240504_144803"
			;;
		"s020_m302")
			export N5_MASK_DATASET="/render/slab_020_to_029/s020_m302_align_no35_horiz_avgshd_ic___mask_20240504_144806"
			;;
		"s021_m253")
			export N5_MASK_DATASET="/render/slab_020_to_029/s021_m253_align_no35_horiz_avgshd_ic___mask_20240504_144809"
			;;
		"s022_m367")
			export N5_MASK_DATASET="/render/slab_020_to_029/s022_m367_align_no35_horiz_avgshd_ic___mask_20240504_144812"
			;;
		"s023_m241")
			export N5_MASK_DATASET="/render/slab_020_to_029/s023_m241_align_no35_horiz_avgshd_ic___mask_20240504_144814"
			;;
		"s024_m362")
			export N5_MASK_DATASET="/render/slab_020_to_029/s024_m362_align_no35_horiz_avgshd_ic___mask_20240504_144817"
			;;
		"s025_m277")
			export N5_MASK_DATASET="/render/slab_020_to_029/s025_m277_align_no35_horiz_avgshd_ic___mask_20240504_144820"
			;;
		"s026_m372")
			export N5_MASK_DATASET="/render/slab_020_to_029/s026_m372_align_no35_horiz_avgshd_ic___mask_20240504_144823"
			;;
		"s027_m275")
			export N5_MASK_DATASET="/render/slab_020_to_029/s027_m275_align_no35_horiz_avgshd_ic___mask_20240504_144826"
			;;
		"s028_m173")
			export N5_MASK_DATASET="/render/slab_020_to_029/s028_m173_align_no35_horiz_avgshd_ic___mask_20240504_144828"
			;;
		"s029_m349")
			export N5_MASK_DATASET="/render/slab_020_to_029/s029_m349_align_no35_horiz_avgshd_ic___mask_20240504_144831"
			;;
		"s030_m016")
			export N5_MASK_DATASET="/render/slab_030_to_039/s030_m016_align_no35_horiz_avgshd_ic___mask_20240504_144834"
			;;
		"s031_m105")
			export N5_MASK_DATASET="/render/slab_030_to_039/s031_m105_align_no35_horiz_avgshd_ic___mask_20240504_144837"
			;;
		"s032_m133")
			export N5_MASK_DATASET="/render/slab_030_to_039/s032_m133_align_no35_horiz_avgshd_ic___mask_20240504_144839"
			;;
		"s033_m039")
			export N5_MASK_DATASET="/render/slab_030_to_039/s033_m039_align_no35_horiz_avgshd_ic___mask_20240504_144842"
			;;
		"s034_m081")
			export N5_MASK_DATASET="/render/slab_030_to_039/s034_m081_align_no35_horiz_avgshd_ic___mask_20240504_144845"
			;;
		"s035_m387")
			export N5_MASK_DATASET="/render/slab_030_to_039/s035_m387_align_no35_horiz_avgshd_ic___mask_20240504_144848"
			;;
		"s036_m252")
			export N5_MASK_DATASET="/render/slab_030_to_039/s036_m252_align_no35_horiz_avgshd_ic___mask_20240504_144850"
			;;
		"s037_m381")
			export N5_MASK_DATASET="/render/slab_030_to_039/s037_m381_align_no35_horiz_avgshd_ic___mask_20240504_144853"
			;;
		"s038_m139")
			export N5_MASK_DATASET="/render/slab_030_to_039/s038_m139_align_no35_horiz_avgshd_ic___mask_20240504_144856"
			;;
		"s039_m295")
			export N5_MASK_DATASET="/render/slab_030_to_039/s039_m295_align_no35_horiz_avgshd_ic___mask_20240504_144859"
			;;
		"s040_m022")
			export N5_MASK_DATASET="/render/slab_040_to_049/s040_m022_align_no35_horiz_avgshd_ic___mask_20240504_144901"
			;;
		"s041_m003")
			export N5_MASK_DATASET="/render/slab_040_to_049/s041_m003_align_no35_horiz_avgshd_ic___mask_20240504_144904"
			;;
		"s042_m070")
			export N5_MASK_DATASET="/render/slab_040_to_049/s042_m070_align_no35_horiz_avgshd_ic___mask_20240504_144907"
			;;
		"s043_m379")
			export N5_MASK_DATASET="/render/slab_040_to_049/s043_m379_align_no35_horiz_avgshd_ic___mask_20240504_144910"
			;;
		"s044_m292")
			export N5_MASK_DATASET="/render/slab_040_to_049/s044_m292_align_no35_horiz_avgshd_ic___mask_20240504_144913"
			;;
		"s045_m296")
			export N5_MASK_DATASET="/render/slab_040_to_049/s045_m296_align_no35_horiz_avgshd_ic___mask_20240504_144916"
			;;
		"s046_m259")
			export N5_MASK_DATASET="/render/slab_040_to_049/s046_m259_align_no35_horiz_avgshd_ic___mask_20240504_144918"
			;;
		"s047_m307")
			export N5_MASK_DATASET="/render/slab_040_to_049/s047_m307_align_no35_horiz_avgshd_ic___mask_20240504_144921"
			;;
		"s048_m044")
			export N5_MASK_DATASET="/render/slab_040_to_049/s048_m044_align_no35_horiz_avgshd_ic___mask_20240504_144924"
			;;
		"s049_m025")
			export N5_MASK_DATASET="/render/slab_040_to_049/s049_m025_align_no35_horiz_avgshd_ic___mask_20240504_144927"
			;;
		"s050_m268")
			export N5_MASK_DATASET="/render/slab_050_to_059/s050_m268_align_no35_horiz_avgshd_ic___mask_20240504_144930"
			;;
		"s051_m287")
			export N5_MASK_DATASET="/render/slab_050_to_059/s051_m287_align_no35_horiz_avgshd_ic___mask_20240504_144932"
			;;
		"s052_m008")
			export N5_MASK_DATASET="/render/slab_050_to_059/s052_m008_align_no35_horiz_avgshd_ic___mask_20240504_144935"
			;;
		"s053_m188")
			export N5_MASK_DATASET="/render/slab_050_to_059/s053_m188_align_no35_horiz_avgshd_ic___mask_20240504_144938"
			;;
		"s054_m326")
			export N5_MASK_DATASET="/render/slab_050_to_059/s054_m326_align_no35_horiz_avgshd_ic___mask_20240504_144941"
			;;
		"s055_m089")
			export N5_MASK_DATASET="/render/slab_050_to_059/s055_m089_align_no35_horiz_avgshd_ic___mask_20240504_144943"
			;;
		"s056_m131")
			export N5_MASK_DATASET="/render/slab_050_to_059/s056_m131_align_no35_horiz_avgshd_ic___mask_20240504_144946"
			;;
		"s057_m055")
			export N5_MASK_DATASET="/render/slab_050_to_059/s057_m055_align_no35_horiz_avgshd_ic___mask_20240504_144949"
			;;
		"s058_m102")
			export N5_MASK_DATASET="/render/slab_050_to_059/s058_m102_align_no35_horiz_avgshd_ic___mask_20240504_144952"
			;;
		"s059_m355")
			export N5_MASK_DATASET="/render/slab_050_to_059/s059_m355_align_no35_horiz_avgshd_ic___mask_20240504_144955"
			;;
		"s060_m162")
			export N5_MASK_DATASET="/render/slab_060_to_069/s060_m162_align_no35_horiz_avgshd_ic___mask_20240504_144957"
			;;
		"s061_m235")
			export N5_MASK_DATASET="/render/slab_060_to_069/s061_m235_align_no35_horiz_avgshd_ic___mask_20240504_145000"
			;;
		"s062_m122")
			export N5_MASK_DATASET="/render/slab_060_to_069/s062_m122_align_no35_horiz_avgshd_ic___mask_20240504_145003"
			;;
		"s063_m054")
			export N5_MASK_DATASET="/render/slab_060_to_069/s063_m054_align_no35_horiz_avgshd_ic___mask_20240504_145006"
			;;
		"s064_m212")
			export N5_MASK_DATASET="/render/slab_060_to_069/s064_m212_align_no35_horiz_avgshd_ic___mask_20240504_145008"
			;;
		"s065_m057")
			export N5_MASK_DATASET="/render/slab_060_to_069/s065_m057_align_no35_horiz_avgshd_ic___mask_20240504_145011"
			;;
		"s066_m210")
			export N5_MASK_DATASET="/render/slab_060_to_069/s066_m210_align_no35_horiz_avgshd_ic___mask_20240504_145014"
			;;
		"s067_m037")
			export N5_MASK_DATASET="/render/slab_060_to_069/s067_m037_align_no35_horiz_avgshd_ic___mask_20240504_145017"
			;;
		"s068_m118")
			export N5_MASK_DATASET="/render/slab_060_to_069/s068_m118_align_no35_horiz_avgshd_ic___mask_20240504_145020"
			;;
		"s069_m390")
			export N5_MASK_DATASET="/render/slab_060_to_069/s069_m390_align_no35_horiz_avgshd_ic___mask_20240504_145022"
			;;
		"s070_m104")
			export N5_MASK_DATASET="/render/slab_070_to_079/s070_m104_align_no35_horiz_avgshd_ic___mask_20240504_145025"
			;;
		"s071_m331")
			export N5_MASK_DATASET="/render/slab_070_to_079/s071_m331_align_no35_horiz_avgshd_ic___mask_20240504_145028"
			;;
		"s072_m150")
			export N5_MASK_DATASET="/render/slab_070_to_079/s072_m150_align_no35_horiz_avgshd_ic___mask_20240504_145031"
			;;
		"s073_m079")
			export N5_MASK_DATASET="/render/slab_070_to_079/s073_m079_align_no35_horiz_avgshd_ic___mask_20240504_145034"
			;;
		"s074_m265")
			export N5_MASK_DATASET="/render/slab_070_to_079/s074_m265_align_no35_horiz_avgshd_ic___mask_20240504_145036"
			;;
		"s075_m119")
			export N5_MASK_DATASET="/render/slab_070_to_079/s075_m119_align_no35_horiz_avgshd_ic___mask_20240504_145039"
			;;
		"s076_m033")
			export N5_MASK_DATASET="/render/slab_070_to_079/s076_m033_align_no35_horiz_avgshd_ic___mask_20240504_145042"
			;;
		"s077_m286")
			export N5_MASK_DATASET="/render/slab_070_to_079/s077_m286_align_no35_horiz_avgshd_ic___mask_20240504_145045"
			;;
		"s078_m279")
			export N5_MASK_DATASET="/render/slab_070_to_079/s078_m279_align_no35_horiz_avgshd_ic___mask_20240504_145047"
			;;
		"s079_m214")
			export N5_MASK_DATASET="/render/slab_070_to_079/s079_m214_align_no35_horiz_avgshd_ic___mask_20240504_145050"
			;;
		"s080_m174")
			export N5_MASK_DATASET="/render/slab_080_to_089/s080_m174_align_no35_horiz_avgshd_ic___mask_20240504_145053"
			;;
		"s081_m049")
			export N5_MASK_DATASET="/render/slab_080_to_089/s081_m049_align_no35_horiz_avgshd_ic___mask_20240504_145056"
			;;
		"s082_m190")
			export N5_MASK_DATASET="/render/slab_080_to_089/s082_m190_align_no35_horiz_avgshd_ic___mask_20240504_145058"
			;;
		"s083_m029")
			export N5_MASK_DATASET="/render/slab_080_to_089/s083_m029_align_no35_horiz_avgshd_ic___mask_20240504_145101"
			;;
		"s084_m069")
			export N5_MASK_DATASET="/render/slab_080_to_089/s084_m069_align_no35_horiz_avgshd_ic___mask_20240504_145104"
			;;
		"s085_m031")
			export N5_MASK_DATASET="/render/slab_080_to_089/s085_m031_align_no35_horiz_avgshd_ic___mask_20240504_145107"
			;;
		"s086_m181")
			export N5_MASK_DATASET="/render/slab_080_to_089/s086_m181_align_no35_horiz_avgshd_ic___mask_20240504_145109"
			;;
		"s087_m155")
			export N5_MASK_DATASET="/render/slab_080_to_089/s087_m155_align_no35_horiz_avgshd_ic___mask_20240504_145112"
			;;
		"s088_m291")
			export N5_MASK_DATASET="/render/slab_080_to_089/s088_m291_align_no35_horiz_avgshd_ic___mask_20240504_145115"
			;;
		"s089_m045")
			export N5_MASK_DATASET="/render/slab_080_to_089/s089_m045_align_no35_horiz_avgshd_ic___mask_20240504_145118"
			;;
		"s090_m114")
			export N5_MASK_DATASET="/render/slab_090_to_099/s090_m114_align_no35_horiz_avgshd_ic___mask_20240504_145121"
			;;
		"s091_m246")
			export N5_MASK_DATASET="/render/slab_090_to_099/s091_m246_align_no35_horiz_avgshd_ic___mask_20240504_145124"
			;;
		"s092_m189")
			export N5_MASK_DATASET="/render/slab_090_to_099/s092_m189_align_no35_horiz_avgshd_ic___mask_20240504_145127"
			;;
		"s093_m228")
			export N5_MASK_DATASET="/render/slab_090_to_099/s093_m228_align_no35_horiz_avgshd_ic___mask_20240504_145129"
			;;
		"s094_m059")
			export N5_MASK_DATASET="/render/slab_090_to_099/s094_m059_align_no35_horiz_avgshd_ic___mask_20240504_145132"
			;;
		"s095_m221")
			export N5_MASK_DATASET="/render/slab_090_to_099/s095_m221_align_no35_horiz_avgshd_ic___mask_20240504_145135"
			;;
		"s096_m132")
			export N5_MASK_DATASET="/render/slab_090_to_099/s096_m132_align_no35_horiz_avgshd_ic___mask_20240504_145138"
			;;
		"s097_m149")
			export N5_MASK_DATASET="/render/slab_090_to_099/s097_m149_align_no35_horiz_avgshd_ic___mask_20240504_145140"
			;;
		"s098_m154")
			export N5_MASK_DATASET="/render/slab_090_to_099/s098_m154_align_no35_horiz_avgshd_ic___mask_20240504_145143"
			;;
		"s099_m233")
			export N5_MASK_DATASET="/render/slab_090_to_099/s099_m233_align_no35_horiz_avgshd_ic___mask_20240504_145146"
			;;
		"s100_m164")
			export N5_MASK_DATASET="/render/slab_100_to_109/s100_m164_align_no35_horiz_avgshd_ic___mask_20240504_145149"
			;;
		"s101_m313")
			export N5_MASK_DATASET="/render/slab_100_to_109/s101_m313_align_no35_horiz_avgshd_ic___mask_20240504_145151"
			;;
		"s102_m240")
			export N5_MASK_DATASET="/render/slab_100_to_109/s102_m240_align_no35_horiz_avgshd_ic___mask_20240504_145154"
			;;
		"s103_m236")
			export N5_MASK_DATASET="/render/slab_100_to_109/s103_m236_align_no35_horiz_avgshd_ic___mask_20240504_145157"
			;;
		"s104_m323")
			export N5_MASK_DATASET="/render/slab_100_to_109/s104_m323_align_no35_horiz_avgshd_ic___mask_20240504_145200"
			;;
		"s105_m397")
			export N5_MASK_DATASET="/render/slab_100_to_109/s105_m397_align_no35_horiz_avgshd_ic___mask_20240504_145203"
			;;
		"s106_m180")
			export N5_MASK_DATASET="/render/slab_100_to_109/s106_m180_align_no35_horiz_avgshd_ic___mask_20240504_145205"
			;;
		"s107_m192")
			export N5_MASK_DATASET="/render/slab_100_to_109/s107_m192_align_no35_horiz_avgshd_ic___mask_20240504_145208"
			;;
		"s108_m157")
			export N5_MASK_DATASET="/render/slab_100_to_109/s108_m157_align_no35_horiz_avgshd_ic___mask_20240504_145211"
			;;
		"s109_m351")
			export N5_MASK_DATASET="/render/slab_100_to_109/s109_m351_align_no35_horiz_avgshd_ic___mask_20240504_145214"
			;;
		"s110_m141")
			export N5_MASK_DATASET="/render/slab_110_to_119/s110_m141_align_no35_horiz_avgshd_ic___mask_20240504_145216"
			;;
		"s111_m117")
			export N5_MASK_DATASET="/render/slab_110_to_119/s111_m117_align_no35_horiz_avgshd_ic___mask_20240504_145219"
			;;
		"s112_m213")
			export N5_MASK_DATASET="/render/slab_110_to_119/s112_m213_align_no35_horiz_avgshd_ic___mask_20240504_145222"
			;;
		"s113_m293")
			export N5_MASK_DATASET="/render/slab_110_to_119/s113_m293_align_no35_horiz_avgshd_ic___mask_20240504_145225"
			;;
		"s114_m094")
			export N5_MASK_DATASET="/render/slab_110_to_119/s114_m094_align_no35_horiz_avgshd_ic___mask_20240504_145228"
			;;
		"s115_m242")
			export N5_MASK_DATASET="/render/slab_110_to_119/s115_m242_align_no35_horiz_avgshd_ic___mask_20240504_145231"
			;;
		"s116_m341")
			export N5_MASK_DATASET="/render/slab_110_to_119/s116_m341_align_no35_horiz_avgshd_ic___mask_20240504_145233"
			;;
		"s117_m023")
			export N5_MASK_DATASET="/render/slab_110_to_119/s117_m023_align_no35_horiz_avgshd_ic___mask_20240504_145236"
			;;
		"s118_m092")
			export N5_MASK_DATASET="/render/slab_110_to_119/s118_m092_align_no35_horiz_avgshd_ic___mask_20240504_145239"
			;;
		"s119_m169")
			export N5_MASK_DATASET="/render/slab_110_to_119/s119_m169_align_no35_horiz_avgshd_ic___mask_20240504_145242"
			;;
		"s120_m324")
			export N5_MASK_DATASET="/render/slab_120_to_129/s120_m324_align_no35_horiz_avgshd_ic___mask_20240504_145244"
			;;
		"s121_m217")
			export N5_MASK_DATASET="/render/slab_120_to_129/s121_m217_align_no35_horiz_avgshd_ic___mask_20240504_145247"
			;;
		"s122_m325")
			export N5_MASK_DATASET="/render/slab_120_to_129/s122_m325_align_no35_horiz_avgshd_ic___mask_20240504_145250"
			;;
		"s123_m357")
			export N5_MASK_DATASET="/render/slab_120_to_129/s123_m357_align_no35_horiz_avgshd_ic___mask_20240504_145253"
			;;
		"s124_m129")
			export N5_MASK_DATASET="/render/slab_120_to_129/s124_m129_align_no35_horiz_avgshd_ic___mask_20240504_145255"
			;;
		"s125_m336")
			export N5_MASK_DATASET="/render/slab_120_to_129/s125_m336_align_no35_horiz_avgshd_ic___mask_20240504_145258"
			;;
		"s126_m013")
			export N5_MASK_DATASET="/render/slab_120_to_129/s126_m013_align_no35_horiz_avgshd_ic___mask_20240504_145301"
			;;
		"s127_m232")
			export N5_MASK_DATASET="/render/slab_120_to_129/s127_m232_align_no35_horiz_avgshd_ic___mask_20240504_145304"
			;;
		"s128_m282")
			export N5_MASK_DATASET="/render/slab_120_to_129/s128_m282_align_no35_horiz_avgshd_ic___mask_20240504_145306"
			;;
		"s129_m318")
			export N5_MASK_DATASET="/render/slab_120_to_129/s129_m318_align_no35_horiz_avgshd_ic___mask_20240504_145309"
			;;
		"s130_m091")
			export N5_MASK_DATASET="/render/slab_130_to_139/s130_m091_align_no35_horiz_avgshd_ic___mask_20240504_145312"
			;;
		"s131_m043")
			export N5_MASK_DATASET="/render/slab_130_to_139/s131_m043_align_no35_horiz_avgshd_ic___mask_20240504_145315"
			;;
		"s132_m140")
			export N5_MASK_DATASET="/render/slab_130_to_139/s132_m140_align_no35_horiz_avgshd_ic___mask_20240504_145317"
			;;
		"s133_m305")
			export N5_MASK_DATASET="/render/slab_130_to_139/s133_m305_align_no35_horiz_avgshd_ic___mask_20240504_145320"
			;;
		"s134_m064")
			export N5_MASK_DATASET="/render/slab_130_to_139/s134_m064_align_no35_horiz_avgshd_ic___mask_20240504_145323"
			;;
		"s135_m078")
			export N5_MASK_DATASET="/render/slab_130_to_139/s135_m078_align_no35_horiz_avgshd_ic___mask_20240504_145326"
			;;
		"s136_m115")
			export N5_MASK_DATASET="/render/slab_130_to_139/s136_m115_align_no35_horiz_avgshd_ic___mask_20240504_145329"
			;;
		"s137_m388")
			export N5_MASK_DATASET="/render/slab_130_to_139/s137_m388_align_no35_horiz_avgshd_ic___mask_20240504_145332"
			;;
		"s138_m290")
			export N5_MASK_DATASET="/render/slab_130_to_139/s138_m290_align_no35_horiz_avgshd_ic___mask_20240504_145335"
			;;
		"s139_m111")
			export N5_MASK_DATASET="/render/slab_130_to_139/s139_m111_align_no35_horiz_avgshd_ic___mask_20240504_145337"
			;;
		"s140_m067")
			export N5_MASK_DATASET="/render/slab_140_to_149/s140_m067_align_no35_horiz_avgshd_ic___mask_20240504_145340"
			;;
		"s141_m238")
			export N5_MASK_DATASET="/render/slab_140_to_149/s141_m238_align_no35_horiz_avgshd_ic___mask_20240504_145343"
			;;
		"s142_m018")
			export N5_MASK_DATASET="/render/slab_140_to_149/s142_m018_align_no35_horiz_avgshd_ic___mask_20240504_145346"
			;;
		"s143_m366")
			export N5_MASK_DATASET="/render/slab_140_to_149/s143_m366_align_no35_horiz_avgshd_ic___mask_20240504_145348"
			;;
		"s144_m321")
			export N5_MASK_DATASET="/render/slab_140_to_149/s144_m321_align_no35_horiz_avgshd_ic___mask_20240504_145351"
			;;
		"s145_m080")
			export N5_MASK_DATASET="/render/slab_140_to_149/s145_m080_align_no35_horiz_avgshd_ic___mask_20240504_145354"
			;;
		"s146_m009")
			export N5_MASK_DATASET="/render/slab_140_to_149/s146_m009_align_no35_horiz_avgshd_ic___mask_20240504_145357"
			;;
		"s147_m375")
			export N5_MASK_DATASET="/render/slab_140_to_149/s147_m375_align_no35_horiz_avgshd_ic___mask_20240504_145359"
			;;
		"s148_m109")
			export N5_MASK_DATASET="/render/slab_140_to_149/s148_m109_align_no35_horiz_avgshd_ic___mask_20240504_145402"
			;;
		"s149_m243")
			export N5_MASK_DATASET="/render/slab_140_to_149/s149_m243_align_no35_horiz_avgshd_ic___mask_20240504_145405"
			;;
		"s150_m280")
			export N5_MASK_DATASET="/render/slab_150_to_159/s150_m280_align_no35_horiz_avgshd_ic___mask_20240504_145408"
			;;
		"s151_m017")
			export N5_MASK_DATASET="/render/slab_150_to_159/s151_m017_align_no35_horiz_avgshd_ic___mask_20240504_145410"
			;;
		"s152_m145")
			export N5_MASK_DATASET="/render/slab_150_to_159/s152_m145_align_no35_horiz_avgshd_ic___mask_20240504_145413"
			;;
		"s153_m205")
			export N5_MASK_DATASET="/render/slab_150_to_159/s153_m205_align_no35_horiz_avgshd_ic___mask_20240504_145416"
			;;
		"s154_m124")
			export N5_MASK_DATASET="/render/slab_150_to_159/s154_m124_align_no35_horiz_avgshd_ic___mask_20240504_145419"
			;;
		"s155_m096")
			export N5_MASK_DATASET="/render/slab_150_to_159/s155_m096_align_no35_horiz_avgshd_ic___mask_20240504_145422"
			;;
		"s156_m198")
			export N5_MASK_DATASET="/render/slab_150_to_159/s156_m198_align_no35_horiz_avgshd_ic___mask_20240504_145424"
			;;
		"s157_m026")
			export N5_MASK_DATASET="/render/slab_150_to_159/s157_m026_align_no35_horiz_avgshd_ic___mask_20240504_145427"
			;;
		"s158_m177")
			export N5_MASK_DATASET="/render/slab_150_to_159/s158_m177_align_no35_horiz_avgshd_ic___mask_20240504_145430"
			;;
		"s159_m365")
			export N5_MASK_DATASET="/render/slab_150_to_159/s159_m365_align_no35_horiz_avgshd_ic___mask_20240504_145433"
			;;
		"s160_m215")
			export N5_MASK_DATASET="/render/slab_160_to_169/s160_m215_align_no35_horiz_avgshd_ic___mask_20240504_145436"
			;;
		"s161_m380")
			export N5_MASK_DATASET="/render/slab_160_to_169/s161_m380_align_no35_horiz_avgshd_ic___mask_20240504_145438"
			;;
		"s162_m250")
			export N5_MASK_DATASET="/render/slab_160_to_169/s162_m250_align_no35_horiz_avgshd_ic___mask_20240504_145441"
			;;
		"s163_m063")
			export N5_MASK_DATASET="/render/slab_160_to_169/s163_m063_align_no35_horiz_avgshd_ic___mask_20240504_145444"
			;;
		"s164_m319")
			export N5_MASK_DATASET="/render/slab_160_to_169/s164_m319_align_no35_horiz_avgshd_ic___mask_20240504_145447"
			;;
		"s165_m058")
			export N5_MASK_DATASET="/render/slab_160_to_169/s165_m058_align_no35_horiz_avgshd_ic___mask_20240504_145449"
			;;
		"s166_m020")
			export N5_MASK_DATASET="/render/slab_160_to_169/s166_m020_align_no35_horiz_avgshd_ic___mask_20240504_145452"
			;;
		"s167_m121")
			export N5_MASK_DATASET="/render/slab_160_to_169/s167_m121_align_no35_horiz_avgshd_ic___mask_20240504_145455"
			;;
		"s168_m076")
			export N5_MASK_DATASET="/render/slab_160_to_169/s168_m076_align_no35_horiz_avgshd_ic___mask_20240504_145458"
			;;
		"s169_m208")
			export N5_MASK_DATASET="/render/slab_160_to_169/s169_m208_align_no35_horiz_avgshd_ic___mask_20240504_145500"
			;;
		"s170_m225")
			export N5_MASK_DATASET="/render/slab_170_to_179/s170_m225_align_no35_horiz_avgshd_ic___mask_20240504_145503"
			;;
		"s171_m260")
			export N5_MASK_DATASET="/render/slab_170_to_179/s171_m260_align_no35_horiz_avgshd_ic___mask_20240504_145506"
			;;
		"s172_m196")
			export N5_MASK_DATASET="/render/slab_170_to_179/s172_m196_align_no35_horiz_avgshd_ic___mask_20240504_145509"
			;;
		"s173_m166")
			export N5_MASK_DATASET="/render/slab_170_to_179/s173_m166_align_no35_horiz_avgshd_ic___mask_20240504_145512"
			;;
		"s174_m134")
			export N5_MASK_DATASET="/render/slab_170_to_179/s174_m134_align_no35_horiz_avgshd_ic___mask_20240504_145514"
			;;
		"s175_m194")
			export N5_MASK_DATASET="/render/slab_170_to_179/s175_m194_align_no35_horiz_avgshd_ic___mask_20240504_145517"
			;;
		"s176_m041")
			export N5_MASK_DATASET="/render/slab_170_to_179/s176_m041_align_no35_horiz_avgshd_ic___mask_20240504_145520"
			;;
		"s177_m146")
			export N5_MASK_DATASET="/render/slab_170_to_179/s177_m146_align_no35_horiz_avgshd_ic___mask_20240504_145523"
			;;
		"s178_m137")
			export N5_MASK_DATASET="/render/slab_170_to_179/s178_m137_align_no35_horiz_avgshd_ic___mask_20240504_145525"
			;;
		"s179_m036")
			export N5_MASK_DATASET="/render/slab_170_to_179/s179_m036_align_no35_horiz_avgshd_ic___mask_20240504_145528"
			;;
		"s180_m147")
			export N5_MASK_DATASET="/render/slab_180_to_189/s180_m147_align_no35_horiz_avgshd_ic___mask_20240504_145531"
			;;
		"s181_m211")
			export N5_MASK_DATASET="/render/slab_180_to_189/s181_m211_align_no35_horiz_avgshd_ic___mask_20240504_145534"
			;;
		"s182_m010")
			export N5_MASK_DATASET="/render/slab_180_to_189/s182_m010_align_no35_horiz_avgshd_ic___mask_20240504_145537"
			;;
		"s183_m264")
			export N5_MASK_DATASET="/render/slab_180_to_189/s183_m264_align_no35_horiz_avgshd_ic___mask_20240504_145540"
			;;
		"s184_m203")
			export N5_MASK_DATASET="/render/slab_180_to_189/s184_m203_align_no35_horiz_avgshd_ic___mask_20240504_145542"
			;;
		"s185_m084")
			export N5_MASK_DATASET="/render/slab_180_to_189/s185_m084_align_no35_horiz_avgshd_ic___mask_20240504_145545"
			;;
		"s186_m247")
			export N5_MASK_DATASET="/render/slab_180_to_189/s186_m247_align_no35_horiz_avgshd_ic___mask_20240504_145548"
			;;
		"s187_m047")
			export N5_MASK_DATASET="/render/slab_180_to_189/s187_m047_align_no35_horiz_avgshd_ic___mask_20240504_145551"
			;;
		"s188_m385")
			export N5_MASK_DATASET="/render/slab_180_to_189/s188_m385_align_no35_horiz_avgshd_ic___mask_20240504_145553"
			;;
		"s189_m315")
			export N5_MASK_DATASET="/render/slab_180_to_189/s189_m315_align_no35_horiz_avgshd_ic___mask_20240504_145556"
			;;
		"s190_m294")
			export N5_MASK_DATASET="/render/slab_190_to_199/s190_m294_align_no35_horiz_avgshd_ic___mask_20240504_145559"
			;;
		"s191_m038")
			export N5_MASK_DATASET="/render/slab_190_to_199/s191_m038_align_no35_horiz_avgshd_ic___mask_20240504_145602"
			;;
		"s192_m086")
			export N5_MASK_DATASET="/render/slab_190_to_199/s192_m086_align_no35_horiz_avgshd_ic___mask_20240504_145604"
			;;
		"s193_m030")
			export N5_MASK_DATASET="/render/slab_190_to_199/s193_m030_align_no35_horiz_avgshd_ic___mask_20240504_145607"
			;;
		"s194_m182")
			export N5_MASK_DATASET="/render/slab_190_to_199/s194_m182_align_no35_horiz_avgshd_ic___mask_20240504_145610"
			;;
		"s195_m128")
			export N5_MASK_DATASET="/render/slab_190_to_199/s195_m128_align_no35_horiz_avgshd_ic___mask_20240504_145613"
			;;
		"s196_m120")
			export N5_MASK_DATASET="/render/slab_190_to_199/s196_m120_align_no35_horiz_avgshd_ic___mask_20240504_145615"
			;;
		"s197_m347")
			export N5_MASK_DATASET="/render/slab_190_to_199/s197_m347_align_no35_horiz_avgshd_ic___mask_20240504_145618"
			;;
		"s198_m306")
			export N5_MASK_DATASET="/render/slab_190_to_199/s198_m306_align_no35_horiz_avgshd_ic___mask_20240504_145621"
			;;
		"s199_m130")
			export N5_MASK_DATASET="/render/slab_190_to_199/s199_m130_align_no35_horiz_avgshd_ic___mask_20240504_145624"
			;;
		"s200_m207")
			export N5_MASK_DATASET="/render/slab_200_to_209/s200_m207_align_no35_horiz_avgshd_ic___mask_20240504_145626"
			;;
		"s201_m056")
			export N5_MASK_DATASET="/render/slab_200_to_209/s201_m056_align_no35_horiz_avgshd_ic___mask_20240504_145629"
			;;
		"s202_m158")
			export N5_MASK_DATASET="/render/slab_200_to_209/s202_m158_align_no35_horiz_avgshd_ic___mask_20240504_145632"
			;;
		"s203_m269")
			export N5_MASK_DATASET="/render/slab_200_to_209/s203_m269_align_no35_horiz_avgshd_ic___mask_20240504_145635"
			;;
		"s204_m237")
			export N5_MASK_DATASET="/render/slab_200_to_209/s204_m237_align_no35_horiz_avgshd_ic___mask_20240504_145638"
			;;
		"s205_m015")
			export N5_MASK_DATASET="/render/slab_200_to_209/s205_m015_align_no35_horiz_avgshd_ic___mask_20240504_145641"
			;;
		"s206_m283")
			export N5_MASK_DATASET="/render/slab_200_to_209/s206_m283_align_no35_horiz_avgshd_ic___mask_20240504_145643"
			;;
		"s207_m263")
			export N5_MASK_DATASET="/render/slab_200_to_209/s207_m263_align_no35_horiz_avgshd_ic___mask_20240504_145646"
			;;
		"s208_m254")
			export N5_MASK_DATASET="/render/slab_200_to_209/s208_m254_align_no35_horiz_avgshd_ic___mask_20240504_145649"
			;;
		"s209_m249")
			export N5_MASK_DATASET="/render/slab_200_to_209/s209_m249_align_no35_horiz_avgshd_ic___mask_20240504_145652"
			;;
		"s210_m062")
			export N5_MASK_DATASET="/render/slab_210_to_219/s210_m062_align_no35_horiz_avgshd_ic___mask_20240504_145654"
			;;
		"s211_m350")
			export N5_MASK_DATASET="/render/slab_210_to_219/s211_m350_align_no35_horiz_avgshd_ic___mask_20240504_145657"
			;;
		"s212_m170")
			export N5_MASK_DATASET="/render/slab_210_to_219/s212_m170_align_no35_horiz_avgshd_ic___mask_20240504_145700"
			;;
		"s213_m386")
			export N5_MASK_DATASET="/render/slab_210_to_219/s213_m386_align_no35_horiz_avgshd_ic___mask_20240504_145703"
			;;
		"s214_m095")
			export N5_MASK_DATASET="/render/slab_210_to_219/s214_m095_align_no35_horiz_avgshd_ic___mask_20240504_145706"
			;;
		"s215_m222")
			export N5_MASK_DATASET="/render/slab_210_to_219/s215_m222_align_no35_horiz_avgshd_ic___mask_20240504_145708"
			;;
		"s216_m271")
			export N5_MASK_DATASET="/render/slab_210_to_219/s216_m271_align_no35_horiz_avgshd_ic___mask_20240504_145711"
			;;
		"s217_m392")
			export N5_MASK_DATASET="/render/slab_210_to_219/s217_m392_align_no35_horiz_avgshd_ic___mask_20240504_145714"
			;;
		"s218_m142")
			export N5_MASK_DATASET="/render/slab_210_to_219/s218_m142_align_no35_horiz_avgshd_ic___mask_20240504_145717"
			;;
		"s219_m199")
			export N5_MASK_DATASET="/render/slab_210_to_219/s219_m199_align_no35_horiz_avgshd_ic___mask_20240504_145719"
			;;
		"s220_m224")
			export N5_MASK_DATASET="/render/slab_220_to_229/s220_m224_align_no35_horiz_avgshd_ic___mask_20240504_145722"
			;;
		"s221_m176")
			export N5_MASK_DATASET="/render/slab_220_to_229/s221_m176_align_no35_horiz_avgshd_ic___mask_20240504_145725"
			;;
		"s222_m309")
			export N5_MASK_DATASET="/render/slab_220_to_229/s222_m309_align_no35_horiz_avgshd_ic___mask_20240504_145728"
			;;
		"s223_m329")
			export N5_MASK_DATASET="/render/slab_220_to_229/s223_m329_align_no35_horiz_avgshd_ic___mask_20240504_145731"
			;;
		"s224_m334")
			export N5_MASK_DATASET="/render/slab_220_to_229/s224_m334_align_no35_horiz_avgshd_ic___mask_20240504_145734"
			;;
		"s225_m358")
			export N5_MASK_DATASET="/render/slab_220_to_229/s225_m358_align_no35_horiz_avgshd_ic___mask_20240504_145737"
			;;
		"s226_m219")
			export N5_MASK_DATASET="/render/slab_220_to_229/s226_m219_align_no35_horiz_avgshd_ic___mask_20240504_145739"
			;;
		"s227_m396")
			export N5_MASK_DATASET="/render/slab_220_to_229/s227_m396_align_no35_horiz_avgshd_ic___mask_20240504_145742"
			;;
		"s228_m363")
			export N5_MASK_DATASET="/render/slab_220_to_229/s228_m363_align_no35_horiz_avgshd_ic___mask_20240504_145745"
			;;
		"s229_m075")
			export N5_MASK_DATASET="/render/slab_220_to_229/s229_m075_align_no35_horiz_avgshd_ic___mask_20240504_145748"
			;;
		"s230_m126")
			export N5_MASK_DATASET="/render/slab_230_to_239/s230_m126_align_no35_horiz_avgshd_ic___mask_20240504_145751"
			;;
		"s231_m304")
			export N5_MASK_DATASET="/render/slab_230_to_239/s231_m304_align_no35_horiz_avgshd_ic___mask_20240504_145753"
			;;
		"s232_m314")
			export N5_MASK_DATASET="/render/slab_230_to_239/s232_m314_align_no35_horiz_avgshd_ic___mask_20240504_145756"
			;;
		"s233_m364")
			export N5_MASK_DATASET="/render/slab_230_to_239/s233_m364_align_no35_horiz_avgshd_ic___mask_20240504_145759"
			;;
		"s234_m289")
			export N5_MASK_DATASET="/render/slab_230_to_239/s234_m289_align_no35_horiz_avgshd_ic___mask_20240504_145802"
			;;
		"s235_m226")
			export N5_MASK_DATASET="/render/slab_230_to_239/s235_m226_align_no35_horiz_avgshd_ic___mask_20240504_145804"
			;;
		"s236_m195")
			export N5_MASK_DATASET="/render/slab_230_to_239/s236_m195_align_no35_horiz_avgshd_ic___mask_20240504_145807"
			;;
		"s237_m267")
			export N5_MASK_DATASET="/render/slab_230_to_239/s237_m267_align_no35_horiz_avgshd_ic___mask_20240504_145810"
			;;
		"s238_m266")
			export N5_MASK_DATASET="/render/slab_230_to_239/s238_m266_align_no35_horiz_avgshd_ic___mask_20240504_145813"
			;;
		"s239_m320")
			export N5_MASK_DATASET="/render/slab_230_to_239/s239_m320_align_no35_horiz_avgshd_ic___mask_20240504_145816"
			;;
		"s240_m001")
			export N5_MASK_DATASET="/render/slab_240_to_249/s240_m001_align_no35_horiz_avgshd_ic___mask_20240504_145818"
			;;
		"s241_m112")
			export N5_MASK_DATASET="/render/slab_240_to_249/s241_m112_align_no35_horiz_avgshd_ic___mask_20240504_145821"
			;;
		"s242_m040")
			export N5_MASK_DATASET="/render/slab_240_to_249/s242_m040_align_no35_horiz_avgshd_ic___mask_20240504_145824"
			;;
		"s243_m274")
			export N5_MASK_DATASET="/render/slab_240_to_249/s243_m274_align_no35_horiz_avgshd_ic___mask_20240504_145827"
			;;
		"s244_m116")
			export N5_MASK_DATASET="/render/slab_240_to_249/s244_m116_align_no35_horiz_avgshd_ic___mask_20240504_145830"
			;;
		"s245_m071")
			export N5_MASK_DATASET="/render/slab_240_to_249/s245_m071_align_no35_horiz_avgshd_ic___mask_20240504_145832"
			;;
		"s246_m052")
			export N5_MASK_DATASET="/render/slab_240_to_249/s246_m052_align_no35_horiz_avgshd_ic___mask_20240504_145835"
			;;
		"s247_m299")
			export N5_MASK_DATASET="/render/slab_240_to_249/s247_m299_align_no35_horiz_avgshd_ic___mask_20240504_145838"
			;;
		"s248_m012")
			export N5_MASK_DATASET="/render/slab_240_to_249/s248_m012_align_no35_horiz_avgshd_ic___mask_20240504_145841"
			;;
		"s249_m391")
			export N5_MASK_DATASET="/render/slab_240_to_249/s249_m391_align_no35_horiz_avgshd_ic___mask_20240504_145843"
			;;
		"s250_m082")
			export N5_MASK_DATASET="/render/slab_250_to_259/s250_m082_align_no35_horiz_avgshd_ic___mask_20240504_145846"
			;;
		"s251_m108")
			export N5_MASK_DATASET="/render/slab_250_to_259/s251_m108_align_no35_horiz_avgshd_ic___mask_20240504_145849"
			;;
		"s252_m028")
			export N5_MASK_DATASET="/render/slab_250_to_259/s252_m028_align_no35_horiz_avgshd_ic___mask_20240504_145852"
			;;
		"s253_m100")
			export N5_MASK_DATASET="/render/slab_250_to_259/s253_m100_align_no35_horiz_avgshd_ic___mask_20240504_145855"
			;;
		"s254_m337")
			export N5_MASK_DATASET="/render/slab_250_to_259/s254_m337_align_no35_horiz_avgshd_ic___mask_20240504_145857"
			;;
		"s255_m103")
			export N5_MASK_DATASET="/render/slab_250_to_259/s255_m103_align_no35_horiz_avgshd_ic___mask_20240504_145900"
			;;
		"s256_m060")
			export N5_MASK_DATASET="/render/slab_250_to_259/s256_m060_align_no35_horiz_avgshd_ic___mask_20240504_145903"
			;;
		"s257_m369")
			export N5_MASK_DATASET="/render/slab_250_to_259/s257_m369_align_no35_horiz_avgshd_ic___mask_20240504_145906"
			;;
		"s258_m223")
			export N5_MASK_DATASET="/render/slab_250_to_259/s258_m223_align_no35_horiz_avgshd_ic___mask_20240504_145908"
			;;
		"s259_m230")
			export N5_MASK_DATASET="/render/slab_250_to_259/s259_m230_align_no35_horiz_avgshd_ic___mask_20240504_145911"
			;;
		"s260_m136")
			export N5_MASK_DATASET="/render/slab_260_to_269/s260_m136_align_no35_horiz_avgshd_ic___mask_20240504_145914"
			;;
		"s261_m000")
			export N5_MASK_DATASET="/render/slab_260_to_269/s261_m000_align_no35_horiz_avgshd_ic___mask_20240504_145917"
			;;
		"s262_m066")
			export N5_MASK_DATASET="/render/slab_260_to_269/s262_m066_align_no35_horiz_avgshd_ic___mask_20240504_145919"
			;;
		"s263_m186")
			export N5_MASK_DATASET="/render/slab_260_to_269/s263_m186_align_no35_horiz_avgshd_ic___mask_20240504_145922"
			;;
		"s264_m335")
			export N5_MASK_DATASET="/render/slab_260_to_269/s264_m335_align_no35_horiz_avgshd_ic___mask_20240504_145925"
			;;
		"s265_m090")
			export N5_MASK_DATASET="/render/slab_260_to_269/s265_m090_align_no35_horiz_avgshd_ic___mask_20240504_145928"
			;;
		"s266_m127")
			export N5_MASK_DATASET="/render/slab_260_to_269/s266_m127_align_no35_horiz_avgshd_ic___mask_20240504_145931"
			;;
		"s267_m308")
			export N5_MASK_DATASET="/render/slab_260_to_269/s267_m308_align_no35_horiz_avgshd_ic___mask_20240504_145933"
			;;
		"s268_m317")
			export N5_MASK_DATASET="/render/slab_260_to_269/s268_m317_align_no35_horiz_avgshd_ic___mask_20240504_145936"
			;;
		"s269_m046")
			export N5_MASK_DATASET="/render/slab_260_to_269/s269_m046_align_no35_horiz_avgshd_ic___mask_20240504_145939"
			;;
		"s270_m024")
			export N5_MASK_DATASET="/render/slab_270_to_279/s270_m024_align_no35_horiz_avgshd_ic___mask_20240504_145942"
			;;
		"s271_m301")
			export N5_MASK_DATASET="/render/slab_270_to_279/s271_m301_align_no35_horiz_avgshd_ic___mask_20240504_145945"
			;;
		"s272_m053")
			export N5_MASK_DATASET="/render/slab_270_to_279/s272_m053_align_no35_horiz_avgshd_ic___mask_20240504_145948"
			;;
		"s273_m019")
			export N5_MASK_DATASET="/render/slab_270_to_279/s273_m019_align_no35_horiz_avgshd_ic___mask_20240504_145950"
			;;
		"s274_m165")
			export N5_MASK_DATASET="/render/slab_270_to_279/s274_m165_align_no35_horiz_avgshd_ic___mask_20240504_145953"
			;;
		"s275_m345")
			export N5_MASK_DATASET="/render/slab_270_to_279/s275_m345_align_no35_horiz_avgshd_ic___mask_20240504_145956"
			;;
		"s276_m204")
			export N5_MASK_DATASET="/render/slab_270_to_279/s276_m204_align_no35_horiz_avgshd_ic___mask_20240504_145959"
			;;
		"s277_m272")
			export N5_MASK_DATASET="/render/slab_270_to_279/s277_m272_align_no35_horiz_avgshd_ic___mask_20240504_150002"
			;;
		"s278_m193")
			export N5_MASK_DATASET="/render/slab_270_to_279/s278_m193_align_no35_horiz_avgshd_ic___mask_20240504_150004"
			;;
		"s279_m161")
			export N5_MASK_DATASET="/render/slab_270_to_279/s279_m161_align_no35_horiz_avgshd_ic___mask_20240504_150007"
			;;
		"s280_m256")
			export N5_MASK_DATASET="/render/slab_280_to_289/s280_m256_align_no35_horiz_avgshd_ic___mask_20240504_150010"
			;;
		"s281_m206")
			export N5_MASK_DATASET="/render/slab_280_to_289/s281_m206_align_no35_horiz_avgshd_ic___mask_20240504_150013"
			;;
		"s282_m220")
			export N5_MASK_DATASET="/render/slab_280_to_289/s282_m220_align_no35_horiz_avgshd_ic___mask_20240504_150015"
			;;
		"s283_m106")
			export N5_MASK_DATASET="/render/slab_280_to_289/s283_m106_align_no35_horiz_avgshd_ic___mask_20240504_150018"
			;;
		"s284_m050")
			export N5_MASK_DATASET="/render/slab_280_to_289/s284_m050_align_no35_horiz_avgshd_ic___mask_20240504_150021"
			;;
		"s285_m201")
			export N5_MASK_DATASET="/render/slab_280_to_289/s285_m201_align_no35_horiz_avgshd_ic___mask_20240504_150024"
			;;
		"s286_m179")
			export N5_MASK_DATASET="/render/slab_280_to_289/s286_m179_align_no35_horiz_avgshd_ic___mask_20240504_150026"
			;;
		"s287_m359")
			export N5_MASK_DATASET="/render/slab_280_to_289/s287_m359_align_no35_horiz_avgshd_ic___mask_20240504_150029"
			;;
		"s288_m276")
			export N5_MASK_DATASET="/render/slab_280_to_289/s288_m276_align_no35_horiz_avgshd_ic___mask_20240504_150032"
			;;
		"s289_m014")
			export N5_MASK_DATASET="/render/slab_280_to_289/s289_m014_align_no35_horiz_avgshd_ic___mask_20240504_150035"
			;;
		"s290_m144")
			export N5_MASK_DATASET="/render/slab_290_to_299/s290_m144_align_no35_horiz_avgshd_ic___mask_20240504_150038"
			;;
		"s291_m262")
			export N5_MASK_DATASET="/render/slab_290_to_299/s291_m262_align_no35_horiz_avgshd_ic___mask_20240504_150040"
			;;
		"s292_m065")
			export N5_MASK_DATASET="/render/slab_290_to_299/s292_m065_align_no35_horiz_avgshd_ic___mask_20240504_150043"
			;;
		"s293_m400")
			export N5_MASK_DATASET="/render/slab_290_to_299/s293_m400_align_no35_horiz_avgshd_ic___mask_20240504_150046"
			;;
		"s294_m123")
			export N5_MASK_DATASET="/render/slab_290_to_299/s294_m123_align_no35_horiz_avgshd_ic___mask_20240504_150049"
			;;
		"s295_m175")
			export N5_MASK_DATASET="/render/slab_290_to_299/s295_m175_align_no35_horiz_avgshd_ic___mask_20240504_150051"
			;;
		"s296_m339")
			export N5_MASK_DATASET="/render/slab_290_to_299/s296_m339_align_no35_horiz_avgshd_ic___mask_20240504_150054"
			;;
		"s297_m048")
			export N5_MASK_DATASET="/render/slab_290_to_299/s297_m048_align_no35_horiz_avgshd_ic___mask_20240504_150057"
			;;
		"s298_m311")
			export N5_MASK_DATASET="/render/slab_290_to_299/s298_m311_align_no35_horiz_avgshd_ic___mask_20240504_150100"
			;;
		"s299_m034")
			export N5_MASK_DATASET="/render/slab_290_to_299/s299_m034_align_no35_horiz_avgshd_ic___mask_20240504_150103"
			;;
		"s300_m160")
			export N5_MASK_DATASET="/render/slab_300_to_309/s300_m160_align_no35_horiz_avgshd_ic___mask_20240504_150105"
			;;
		"s301_m378")
			export N5_MASK_DATASET="/render/slab_300_to_309/s301_m378_align_no35_horiz_avgshd_ic___mask_20240504_150108"
			;;
		"s302_m184")
			export N5_MASK_DATASET="/render/slab_300_to_309/s302_m184_align_no35_horiz_avgshd_ic___mask_20240504_150111"
			;;
		"s303_m083")
			export N5_MASK_DATASET="/render/slab_300_to_309/s303_m083_align_no35_horiz_avgshd_ic___mask_20240504_150114"
			;;
		"s304_m370")
			export N5_MASK_DATASET="/render/slab_300_to_309/s304_m370_align_no35_horiz_avgshd_ic___mask_20240504_150116"
			;;
		"s305_m035")
			export N5_MASK_DATASET="/render/slab_300_to_309/s305_m035_align_no35_horiz_avgshd_ic___mask_20240504_150119"
			;;
		"s306_m340")
			export N5_MASK_DATASET="/render/slab_300_to_309/s306_m340_align_no35_horiz_avgshd_ic___mask_20240504_150122"
			;;
		"s307_m006")
			export N5_MASK_DATASET="/render/slab_300_to_309/s307_m006_align_no35_horiz_avgshd_ic___mask_20240504_150125"
			;;
		"s308_m098")
			export N5_MASK_DATASET="/render/slab_300_to_309/s308_m098_align_no35_horiz_avgshd_ic___mask_20240504_150127"
			;;
		"s309_m110")
			export N5_MASK_DATASET="/render/slab_300_to_309/s309_m110_align_no35_horiz_avgshd_ic___mask_20240504_150130"
			;;
		"s310_m368")
			export N5_MASK_DATASET="/render/slab_310_to_319/s310_m368_align_no35_horiz_avgshd_ic___mask_20240504_152605"
			;;
		"s311_m297")
			export N5_MASK_DATASET="/render/slab_310_to_319/s311_m297_align_no35_horiz_avgshd_ic___mask_20240504_152608"
			;;
		"s312_m171")
			export N5_MASK_DATASET="/render/slab_310_to_319/s312_m171_align_no35_horiz_avgshd_ic___mask_20240504_152611"
			;;
		"s313_m298")
			export N5_MASK_DATASET="/render/slab_310_to_319/s313_m298_align_no35_horiz_avgshd_ic___mask_20240504_152614"
			;;
		"s314_m338")
			export N5_MASK_DATASET="/render/slab_310_to_319/s314_m338_align_no35_horiz_avgshd_ic___mask_20240504_152617"
			;;
		"s315_m303")
			export N5_MASK_DATASET="/render/slab_310_to_319/s315_m303_align_no35_horiz_avgshd_ic___mask_20240504_152619"
			;;
		"s316_m068")
			export N5_MASK_DATASET="/render/slab_310_to_319/s316_m068_align_no35_horiz_avgshd_ic___mask_20240504_152622"
			;;
		"s317_m361")
			export N5_MASK_DATASET="/render/slab_310_to_319/s317_m361_align_no35_horiz_avgshd_ic___mask_20240504_152625"
			;;
		"s318_m389")
			export N5_MASK_DATASET="/render/slab_310_to_319/s318_m389_align_no35_horiz_avgshd_ic___mask_20240504_152628"
			;;
		"s319_m002")
			export N5_MASK_DATASET="/render/slab_310_to_319/s319_m002_align_no35_horiz_avgshd_ic___mask_20240504_152630"
			;;
		"s320_m021")
			export N5_MASK_DATASET="/render/slab_320_to_329/s320_m021_align_no35_horiz_avgshd_ic___mask_20240504_152633"
			;;
		"s321_m101")
			export N5_MASK_DATASET="/render/slab_320_to_329/s321_m101_align_no35_horiz_avgshd_ic___mask_20240504_152636"
			;;
		"s322_m005")
			export N5_MASK_DATASET="/render/slab_320_to_329/s322_m005_align_no35_horiz_avgshd_ic___mask_20240504_152639"
			;;
		"s323_m354")
			export N5_MASK_DATASET="/render/slab_320_to_329/s323_m354_align_no35_horiz_avgshd_ic___mask_20240504_152642"
			;;
		"s324_m156")
			export N5_MASK_DATASET="/render/slab_320_to_329/s324_m156_align_no35_horiz_avgshd_ic___mask_20240504_152644"
			;;
		"s325_m245")
			export N5_MASK_DATASET="/render/slab_320_to_329/s325_m245_align_no35_horiz_avgshd_ic___mask_20240504_152647"
			;;
		"s326_m200")
			export N5_MASK_DATASET="/render/slab_320_to_329/s326_m200_align_no35_horiz_avgshd_ic___mask_20240504_152650"
			;;
		"s327_m244")
			export N5_MASK_DATASET="/render/slab_320_to_329/s327_m244_align_no35_horiz_avgshd_ic___mask_20240504_152653"
			;;
		"s328_m135")
			export N5_MASK_DATASET="/render/slab_320_to_329/s328_m135_align_no35_horiz_avgshd_ic___mask_20240504_152656"
			;;
		"s329_m401")
			export N5_MASK_DATASET="/render/slab_320_to_329/s329_m401_align_no35_horiz_avgshd_ic___mask_20240504_152658"
			;;
		"s330_m085")
			export N5_MASK_DATASET="/render/slab_330_to_339/s330_m085_align_no35_horiz_avgshd_ic___mask_20240504_152701"
			;;
		"s331_m251")
			export N5_MASK_DATASET="/render/slab_330_to_339/s331_m251_align_no35_horiz_avgshd_ic___mask_20240504_152704"
			;;
		"s332_m027")
			export N5_MASK_DATASET="/render/slab_330_to_339/s332_m027_align_no35_horiz_avgshd_ic___mask_20240504_152707"
			;;
		"s333_m163")
			export N5_MASK_DATASET="/render/slab_330_to_339/s333_m163_align_no35_horiz_avgshd_ic___mask_20240504_152709"
			;;
		"s334_m343")
			export N5_MASK_DATASET="/render/slab_330_to_339/s334_m343_align_no35_horiz_avgshd_ic___mask_20240504_152712"
			;;
		"s335_m011")
			export N5_MASK_DATASET="/render/slab_330_to_339/s335_m011_align_no35_horiz_avgshd_ic___mask_20240504_152715"
			;;
		"s336_m373")
			export N5_MASK_DATASET="/render/slab_330_to_339/s336_m373_align_no35_horiz_avgshd_ic___mask_20240504_152718"
			;;
		"s337_m394")
			export N5_MASK_DATASET="/render/slab_330_to_339/s337_m394_align_no35_horiz_avgshd_ic___mask_20240504_152721"
			;;
		"s338_m332")
			export N5_MASK_DATASET="/render/slab_330_to_339/s338_m332_align_no35_horiz_avgshd_ic___mask_20240504_152723"
			;;
		"s339_m032")
			export N5_MASK_DATASET="/render/slab_330_to_339/s339_m032_align_no35_horiz_avgshd_ic___mask_20240504_152726"
			;;
		"s340_m371")
			export N5_MASK_DATASET="/render/slab_340_to_349/s340_m371_align_no35_horiz_avgshd_ic___mask_20240504_152729"
			;;
		"s341_m356")
			export N5_MASK_DATASET="/render/slab_340_to_349/s341_m356_align_no35_horiz_avgshd_ic___mask_20240504_152732"
			;;
		"s342_m191")
			export N5_MASK_DATASET="/render/slab_340_to_349/s342_m191_align_no35_horiz_avgshd_ic___mask_20240504_152734"
			;;
		"s343_m261")
			export N5_MASK_DATASET="/render/slab_340_to_349/s343_m261_align_no35_horiz_avgshd_ic___mask_20240504_152737"
			;;
		"s344_m216")
			export N5_MASK_DATASET="/render/slab_340_to_349/s344_m216_align_no35_horiz_avgshd_ic___mask_20240504_152740"
			;;
		"s345_m327")
			export N5_MASK_DATASET="/render/slab_340_to_349/s345_m327_align_no35_horiz_avgshd_ic___mask_20240504_152743"
			;;
		"s346_m312")
			export N5_MASK_DATASET="/render/slab_340_to_349/s346_m312_align_no35_horiz_avgshd_ic___mask_20240504_152746"
			;;
		"s347_m342")
			export N5_MASK_DATASET="/render/slab_340_to_349/s347_m342_align_no35_horiz_avgshd_ic___mask_20240504_152748"
			;;
		"s348_m061")
			export N5_MASK_DATASET="/render/slab_340_to_349/s348_m061_align_no35_horiz_avgshd_ic___mask_20240504_152751"
			;;
		"s349_m288")
			export N5_MASK_DATASET="/render/slab_340_to_349/s349_m288_align_no35_horiz_avgshd_ic___mask_20240504_152754"
			;;
		"s350_m352")
			export N5_MASK_DATASET="/render/slab_350_to_359/s350_m352_align_no35_horiz_avgshd_ic___mask_20240504_152757"
			;;
		"s351_m218")
			export N5_MASK_DATASET="/render/slab_350_to_359/s351_m218_align_no35_horiz_avgshd_ic___mask_20240504_152800"
			;;
		"s352_m234")
			export N5_MASK_DATASET="/render/slab_350_to_359/s352_m234_align_no35_horiz_avgshd_ic___mask_20240504_152802"
			;;
		"s353_m042")
			export N5_MASK_DATASET="/render/slab_350_to_359/s353_m042_align_no35_horiz_avgshd_ic___mask_20240504_152805"
			;;
		"s354_m093")
			export N5_MASK_DATASET="/render/slab_350_to_359/s354_m093_align_no35_horiz_avgshd_ic___mask_20240504_152808"
			;;
		"s355_m310")
			export N5_MASK_DATASET="/render/slab_350_to_359/s355_m310_align_no35_horiz_avgshd_ic___mask_20240504_152811"
			;;
		"s356_m197")
			export N5_MASK_DATASET="/render/slab_350_to_359/s356_m197_align_no35_horiz_avgshd_ic___mask_20240504_152813"
			;;
		"s357_m051")
			export N5_MASK_DATASET="/render/slab_350_to_359/s357_m051_align_no35_horiz_avgshd_ic___mask_20240504_152816"
			;;
		"s358_m074")
			export N5_MASK_DATASET="/render/slab_350_to_359/s358_m074_align_no35_horiz_avgshd_ic___mask_20240504_152819"
			;;
		"s359_m248")
			export N5_MASK_DATASET="/render/slab_350_to_359/s359_m248_align_no35_horiz_avgshd_ic___mask_20240504_152822"
			;;
		"s360_m346")
			export N5_MASK_DATASET="/render/slab_360_to_369/s360_m346_align_no35_horiz_avgshd_ic___mask_20240504_152825"
			;;
		"s361_m125")
			export N5_MASK_DATASET="/render/slab_360_to_369/s361_m125_align_no35_horiz_avgshd_ic___mask_20240504_152827"
			;;
		"s362_m255")
			export N5_MASK_DATASET="/render/slab_360_to_369/s362_m255_align_no35_horiz_avgshd_ic___mask_20240504_152830"
			;;
		"s363_m344")
			export N5_MASK_DATASET="/render/slab_360_to_369/s363_m344_align_no35_horiz_avgshd_ic___mask_20240504_152833"
			;;
		"s364_m374")
			export N5_MASK_DATASET="/render/slab_360_to_369/s364_m374_align_no35_horiz_avgshd_ic___mask_20240504_152836"
			;;
		"s365_m383")
			export N5_MASK_DATASET="/render/slab_360_to_369/s365_m383_align_no35_horiz_avgshd_ic___mask_20240504_152838"
			;;
		"s366_m088")
			export N5_MASK_DATASET="/render/slab_360_to_369/s366_m088_align_no35_horiz_avgshd_ic___mask_20240504_152841"
			;;
		"s367_m007")
			export N5_MASK_DATASET="/render/slab_360_to_369/s367_m007_align_no35_horiz_avgshd_ic___mask_20240504_152844"
			;;
		"s368_m257")
			export N5_MASK_DATASET="/render/slab_360_to_369/s368_m257_align_no35_horiz_avgshd_ic___mask_20240504_152847"
			;;
		"s369_m143")
			export N5_MASK_DATASET="/render/slab_360_to_369/s369_m143_align_no35_horiz_avgshd_ic___mask_20240504_152849"
			;;
		"s370_m159")
			export N5_MASK_DATASET="/render/slab_370_to_379/s370_m159_align_no35_horiz_avgshd_ic___mask_20240504_152852"
			;;
		"s371_m087")
			export N5_MASK_DATASET="/render/slab_370_to_379/s371_m087_align_no35_horiz_avgshd_ic___mask_20240504_152855"
			;;
		"s372_m402")
			export N5_MASK_DATASET="/render/slab_370_to_379/s372_m402_align_no35_horiz_avgshd_ic___mask_20240504_152858"
			;;
		"s373_m258")
			export N5_MASK_DATASET="/render/slab_370_to_379/s373_m258_align_no35_horiz_avgshd_ic___mask_20240504_152901"
			;;
		"s374_m077")
			export N5_MASK_DATASET="/render/slab_370_to_379/s374_m077_align_no35_horiz_avgshd_ic___mask_20240504_152903"
			;;
		"s375_m284")
			export N5_MASK_DATASET="/render/slab_370_to_379/s375_m284_align_no35_horiz_avgshd_ic___mask_20240504_152906"
			;;
		"s376_m398")
			export N5_MASK_DATASET="/render/slab_370_to_379/s376_m398_align_no35_horiz_avgshd_ic___mask_20240504_152909"
			;;
		"s377_m202")
			export N5_MASK_DATASET="/render/slab_370_to_379/s377_m202_align_no35_horiz_avgshd_ic___mask_20240504_152912"
			;;
		"s378_m376")
			export N5_MASK_DATASET="/render/slab_370_to_379/s378_m376_align_no35_horiz_avgshd_ic___mask_20240504_152915"
			;;
		"s379_m229")
			export N5_MASK_DATASET="/render/slab_370_to_379/s379_m229_align_no35_horiz_avgshd_ic___mask_20240504_152917"
			;;
		"s380_m382")
			export N5_MASK_DATASET="/render/slab_380_to_389/s380_m382_align_no35_horiz_avgshd_ic___mask_20240504_152920"
			;;
		"s381_m377")
			export N5_MASK_DATASET="/render/slab_380_to_389/s381_m377_align_no35_horiz_avgshd_ic___mask_20240504_152923"
			;;
		"s382_m328")
			export N5_MASK_DATASET="/render/slab_380_to_389/s382_m328_align_no35_horiz_avgshd_ic___mask_20240504_152926"
			;;
		"s383_m004")
			export N5_MASK_DATASET="/render/slab_380_to_389/s383_m004_align_no35_horiz_avgshd_ic___mask_20240504_152928"
			;;
		"s384_m384")
			export N5_MASK_DATASET="/render/slab_380_to_389/s384_m384_align_no35_horiz_avgshd_ic___mask_20240504_152931"
			;;
		"s385_m227")
			export N5_MASK_DATASET="/render/slab_380_to_389/s385_m227_align_no35_horiz_avgshd_ic___mask_20240504_152934"
			;;
		"s386_m270")
			export N5_MASK_DATASET="/render/slab_380_to_389/s386_m270_align_no35_horiz_avgshd_ic___mask_20240504_152937"
			;;
		"s387_m187")
			export N5_MASK_DATASET="/render/slab_380_to_389/s387_m187_align_no35_horiz_avgshd_ic___mask_20240504_152940"
			;;
		"s388_m072")
			export N5_MASK_DATASET="/render/slab_380_to_389/s388_m072_align_no35_horiz_avgshd_ic___mask_20240504_152942"
			;;
		"s389_m322")
			export N5_MASK_DATASET="/render/slab_380_to_389/s389_m322_align_no35_horiz_avgshd_ic___mask_20240504_152945"
			;;
		"s390_m273")
			export N5_MASK_DATASET="/render/slab_390_to_399/s390_m273_align_no35_horiz_avgshd_ic___mask_20240504_152948"
			;;
		"s391_m393")
			export N5_MASK_DATASET="/render/slab_390_to_399/s391_m393_align_no35_horiz_avgshd_ic___mask_20240504_152951"
			;;
		"s392_m168")
			export N5_MASK_DATASET="/render/slab_390_to_399/s392_m168_align_no35_horiz_avgshd_ic___mask_20240504_152953"
			;;
		"s393_m138")
			export N5_MASK_DATASET="/render/slab_390_to_399/s393_m138_align_no35_horiz_avgshd_ic___mask_20240504_152956"
			;;
		"s394_m360")
			export N5_MASK_DATASET="/render/slab_390_to_399/s394_m360_align_no35_horiz_avgshd_ic___mask_20240504_152959"
			;;
		"s395_m113")
			export N5_MASK_DATASET="/render/slab_390_to_399/s395_m113_align_no35_horiz_avgshd_ic___mask_20240504_153002"
			;;
		"s396_m153")
			export N5_MASK_DATASET="/render/slab_390_to_399/s396_m153_align_no35_horiz_avgshd_ic___mask_20240504_153005"
			;;
		"s397_m148")
			export N5_MASK_DATASET="/render/slab_390_to_399/s397_m148_align_no35_horiz_avgshd_ic___mask_20240504_153007"
			;;
		"s398_m183")
			export N5_MASK_DATASET="/render/slab_390_to_399/s398_m183_align_no35_horiz_avgshd_ic___mask_20240504_153010"
			;;
		"s399_m185")
			export N5_MASK_DATASET="/render/slab_390_to_399/s399_m185_align_no35_horiz_avgshd_ic___mask_20240504_153013"
			;;
		"s400_m152")
			export N5_MASK_DATASET="/render/slab_400_to_402/s400_m152_align_no35_horiz_avgshd_ic___mask_20240504_153016"
			;;
		"s401_m353")
			export N5_MASK_DATASET="/render/slab_400_to_402/s401_m353_align_no35_horiz_avgshd_ic___mask_20240504_153018"
			;;
		"s402_m399")
			export N5_MASK_DATASET="/render/slab_400_to_402/s402_m399_align_no35_horiz_avgshd_ic___mask_20240504_153021"
			;;
	esac
}

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
