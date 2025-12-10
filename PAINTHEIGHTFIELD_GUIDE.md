# PaintHeightField Usage Guide - Google Cloud Storage

## Your Dataset Structure

Based on your height field location:
```
gs://janelia-spark-test/hess_wafers_60_61_export/heightfields_b240/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/max
```

**Parsing the structure:**
- **Bucket**: `gs://janelia-spark-test`
- **Base path**: `hess_wafers_60_61_export`
- **Height fields root**: `heightfields_b240/w61_serial_070_to_079`
- **Slab/section**: `w61_s079_r00_gc_par_align_ic2d___norm-layer`
- **Scale**: `s1` (typically 4x4x4 or 6x6x1 downsampling)
- **Surface**: `max` (bottom surface) or `min` (top surface)

## Determining Required Parameters

### 1. Find the Raw Data Path

The raw data is likely at:
```
gs://janelia-spark-test/hess_wafers_60_61_export/[raw_data_directory]/w61_s079_r00_gc_par_align_ic2d___norm-layer
```

**Common naming patterns:**
- `align_ic2d___norm-layer` suggests intensity-corrected, normalized data
- Might be under: `raw/`, `v5_acquire_align_ic/`, `z_corr/`, or similar

**To find it, check:**
```bash
gsutil ls gs://janelia-spark-test/hess_wafers_60_61_export/
```

Look for directories containing the same section name (`w61_s079_r00_gc_par_align_ic2d___norm-layer`)

### 2. Determine Downsampling Scale

**Important**: Height field `downsamplingFactors` are stored at the **parent directory level** (e.g., at `s1/`), NOT in the `min/` or `max/` subdirectories.

```bash
# Check height field scale - look at parent level (s1/)
gsutil cat gs://janelia-spark-test/hess_wafers_60_61_export/heightfields_b240/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/attributes.json

# Check raw data s1 attributes (should match)
gsutil cat gs://janelia-spark-test/hess_wafers_60_61_export/[raw_path]/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/attributes.json
```

Look for:
```json
{
  "downsamplingFactors": [2.0, 2.0, 1.0]  // typical for Multi-SEM
}
```

The min/ and max/ subdirectories contain the actual height field data but NOT the scale metadata.

### 3. Determine N5 Container Path

**Option A: Shared container**
If both raw data and height fields are in the same N5 container:
```bash
--n5Path=gs://janelia-spark-test/hess_wafers_60_61_export/[container_name].n5
```

**Option B: Separate containers**
```bash
--n5Path=gs://janelia-spark-test/hess_wafers_60_61_export/[raw_container].n5
--n5FieldPath=gs://janelia-spark-test/hess_wafers_60_61_export/[heightfield_container].n5
```

## Example Command Templates

### Template 1: Same Container (Most Common)
```bash
java -Xmx16G -cp /path/to/hot-knife-0.0.7-SNAPSHOT.jar \
  org.janelia.saalfeldlab.hotknife.tools.PaintHeightField \
  --n5Path="gs://janelia-spark-test/hess_wafers_60_61_export/[DATASET].n5" \
  --n5Raw="/raw/w61_s079_r00_gc_par_align_ic2d___norm-layer" \
  --n5Field="/heightfields_b240/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/max" \
  --n5FieldOutput="/heightfields_b240_fixed/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/max" \
  --scale=2,2,1 \
  --offset=0 \
  --multiSem
```

### Template 2: Separate Containers
```bash
java -Xmx16G -cp /path/to/hot-knife-0.0.7-SNAPSHOT.jar \
  org.janelia.saalfeldlab.hotknife.tools.PaintHeightField \
  --n5Path="gs://janelia-spark-test/hess_wafers_60_61_export/raw_data.n5" \
  --n5FieldPath="gs://janelia-spark-test/hess_wafers_60_61_export/heightfields.n5" \
  --n5Raw="/w61_s079_r00_gc_par_align_ic2d___norm-layer" \
  --n5Field="/heightfields_b240/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/max" \
  --n5FieldOutput="/heightfields_b240_fixed/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/max" \
  --scale=2,2,1 \
  --offset=0 \
  --multiSem
```

### Template 3: With Height Field Offset (for DL predictions)
```bash
java -Xmx16G -cp /path/to/hot-knife-0.0.7-SNAPSHOT.jar \
  org.janelia.saalfeldlab.hotknife.tools.PaintHeightField \
  --n5Path="gs://janelia-spark-test/hess_wafers_60_61_export/[DATASET].n5" \
  --n5Raw="/raw/w61_s079_r00_gc_par_align_ic2d___norm-layer" \
  --n5Field="/heightfields_b240/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/max" \
  --n5FieldOutput="/heightfields_b240_fixed/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/max" \
  --scale=4,4,4 \
  --offset=5 \
  --heightfieldOffset=62,62,62 \
  --multiSem
```

## Parameter Checklist

Before running, verify:

- [ ] **--n5Path**: Base N5 container with raw data exists
- [ ] **--n5Raw**: Raw data path within N5 (check with `gsutil ls`)
- [ ] **--n5Field**: Input height field path exists
- [ ] **--n5FieldOutput**: Output path (can be same as input to overwrite)
- [ ] **--scale**: Matches height field downsampling (check attributes.json)
- [ ] **--offset**: Typical values 3-10 (controls z=0 surface position)
- [ ] **--multiSem**: Add this flag for Multi-SEM data
- [ ] **Memory**: Use -Xmx16G or higher for large datasets

## Finding Your Exact Paths

### Step 1: List bucket structure
```bash
gsutil ls gs://janelia-spark-test/hess_wafers_60_61_export/
```

### Step 2: Find raw data directory
```bash
# Look for directories like:
# - raw/
# - align_ic2d/
# - v5_acquire_align_ic/
# - z_corr/

gsutil ls gs://janelia-spark-test/hess_wafers_60_61_export/[raw_directory]/
```

### Step 3: Verify height field exists
```bash
gsutil ls gs://janelia-spark-test/hess_wafers_60_61_export/heightfields_b240/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/
```

Should show: `min/` and `max/` subdirectories

### Step 4: Check attributes
```bash
# Height field scale (at parent s1/ level - IMPORTANT!)
gsutil cat gs://janelia-spark-test/hess_wafers_60_61_export/heightfields_b240/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/attributes.json

# Height field data attributes (dimensions, avg, etc.)
gsutil cat gs://janelia-spark-test/hess_wafers_60_61_export/heightfields_b240/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/max/attributes.json

# Raw data s1 attributes (to confirm scale matches)
gsutil cat gs://janelia-spark-test/hess_wafers_60_61_export/[raw_path]/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/attributes.json
```

## Common Issues

### Issue 1: Cannot find raw data
**Solution**: The raw data might be in a different N5 container or have a different naming convention. Check:
```bash
gsutil ls -r gs://janelia-spark-test/hess_wafers_60_61_export/ | grep w61_s079
```

### Issue 2: Scale mismatch
**Symptom**: Height field and raw data don't align properly
**Solution**: Verify both have same downsampling at s1:
```bash
# Compare downsamplingFactors in both attributes.json files
```

### Issue 3: N5 container not recognized
**Symptom**: Error about N5 format detection
**Solution**: Ensure path ends in `.n5` or `.zarr`, or contains proper metadata files

### Issue 4: Memory errors
**Symptom**: OutOfMemoryError during startup
**Solution**: Increase heap size: `-Xmx32G` or higher

## Testing Minimal Command

Start with a simple test to verify paths are correct:
```bash
# Test 1: Just open the height field (no output)
java -Xmx8G -cp /path/to/hot-knife.jar \
  org.janelia.saalfeldlab.hotknife.tools.PaintHeightField \
  --n5Path="gs://janelia-spark-test/hess_wafers_60_61_export/[DATASET].n5" \
  --n5Raw="/[RAW_PATH]" \
  --n5Field="/heightfields_b240/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/max" \
  --n5FieldOutput="/tmp/test_output" \
  --scale=2,2,1 \
  --offset=0 \
  --multiSem
```

If BigDataViewer opens successfully, your paths are correct!

## Next Steps

1. Run `gsutil ls` commands above to find exact paths
2. Check `attributes.json` files to confirm scales
3. Fill in template with correct paths
4. Test with minimal command
5. If successful, set `--n5FieldOutput` to desired location
6. Edit and save with Ctrl+S

## Editing Both Surfaces

Remember to edit both `min` (top) and `max` (bottom) surfaces:

```bash
# Edit top surface (min)
java -Xmx16G -cp hot-knife.jar org.janelia.saalfeldlab.hotknife.tools.PaintHeightField \
  --n5Path="..." \
  --n5Field=".../s1/min" \
  --n5FieldOutput=".../s1/min" \
  --scale=4,4,4 --offset=5 --multiSem

# Edit bottom surface (max)
java -Xmx16G -cp hot-knife.jar org.janelia.saalfeldlab.hotknife.tools.PaintHeightField \
  --n5Path="..." \
  --n5Field=".../s1/max" \
  --n5FieldOutput=".../s1/max" \
  --scale=4,4,4 --offset=5 --multiSem
```
