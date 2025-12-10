# SparkComputeCostMultiSem Debug Mode

## Overview
This branch (`cloud-cost-debug`) adds debug capabilities to `SparkComputeCostMultiSem` to help diagnose cost function issues by processing only specific blocks from large datasets and visualizing intermediate results.

**Important:** When debug mode is enabled, **no data is written to N5**. All N5Writer operations are skipped, including dataset creation, block writing, downsampling, and surface fitting. This ensures debug runs don't modify or create any output files.

## New Debug Options

### `--debugMode`
Enable debug mode to process only a subset of blocks instead of the entire dataset.
- **No N5 writing occurs** - all output operations are disabled
- No N5Writer is created
- No blocks are saved
- No downsampling is performed
- No surface fitting is performed
- **ImageJ windows automatically displayed** showing intermediate results:
  - **Input**: Raw input data for the selected block
  - **Mask**: Mask data (if provided)
  - **Derivative**: Z-derivative computation (bright-to-dark transitions)
  - **Derivative Converted**: Scaled derivative values
  - **Derivative Smoothed**: Gaussian-smoothed derivative (if `--smoothCost` enabled)
  - **Cost Block**: Final computed cost function

### `--debugBlockX <number>`
X coordinate of the specific block to process (e.g., 53).
- Must be used together with `--debugBlockY`
- If omitted, processes just the middle block

### `--debugBlockY <number>`
Y coordinate of the specific block to process (e.g., 34).
- Must be used together with `--debugBlockX`
- If omitted, processes just the middle block

## Cost Function Parameters

### `--topLayerCost <value>`
Cost value to use for the top Z layer (default: 105).
- Controls surface detection bias at the top of the volume
- Lower values encourage surfaces at the top boundary
- Range: 0-255

### `--bottomLayerCost <value>`
Cost value to use for the bottom Z layer (default: 230).
- Controls surface detection bias at the bottom of the volume
- Higher values discourage surfaces at the bottom boundary
- Range: 0-255

**Note**: These parameters replace the previous `--outOfBoundsValue` approach, providing independent control over top and bottom boundary costs.

## Usage Examples

### Example 1: Process a specific block
```bash
/path/to/spark-submit \
  --class org.janelia.saalfeldlab.hotknife.SparkComputeCostMultiSem \
  hot-knife.jar \
  --inputN5Path s3://my-bucket/dataset.n5 \
  --outputN5Path s3://my-bucket/output.n5 \
  --inputN5Group /raw/s0 \
  --costN5Group /cost \
  --debugMode \
  --debugBlockX 53 \
  --debugBlockY 34
```

### Example 2: Process middle block (auto-select center)
```bash
/path/to/spark-submit \
  --class org.janelia.saalfeldlab.hotknife.SparkComputeCostMultiSem \
  hot-knife.jar \
  --inputN5Path /path/to/dataset.n5 \
  --outputN5Path /path/to/output.n5 \
  --inputN5Group /raw/s0 \
  --costN5Group /cost \
  --debugMode
```
This will process just the middle block of the dataset and display all images.

### Example 3: Process with mask and smoothing
```bash
/path/to/spark-submit \
  --class org.janelia.saalfeldlab.hotknife.SparkComputeCostMultiSem \
  hot-knife.jar \
  --inputN5Path /path/to/dataset.n5 \
  --outputN5Path /path/to/output.n5 \
  --inputN5Group /raw/s0 \
  --costN5Group /cost \
  --maskN5Group /mask/s0 \
  --median \
  --smoothCost \
  --debugMode \
  --debugBlockX 25 \
  --debugBlockY 15
```

## Finding Block Coordinates

### Method 1: From previous runs
Look at the console output from a full run to see which blocks are being processed:
```
Processing grid coord: 53 34
```

### Method 2: Calculate from spatial coordinates
If you know the physical location (x,y) in the dataset where the cost function looks wrong:
1. Get the block size: `blockX = costBlockSize[0]`, `blockY = costBlockSize[1]`
2. Get the cost steps: `stepX = costSteps[0]`, `stepY = costSteps[1]`
3. Calculate:
   - `gridX = x / (blockX * stepX)`
   - `gridY = y / (blockY * stepY)`

### Method 3: Explore dataset structure
```bash
# List N5 attributes
python -c "
import zarr
z = zarr.open('path/to/dataset.n5', 'r')
print('Dataset shape:', z['raw/s0'].shape)
print('Block size:', z['raw/s0'].chunks)
"
```

## Understanding the Output

### Console Output
When debug mode is enabled, you'll see messages prefixed with "Debug mode:":
```
Debug mode: === DEBUG MODE ENABLED ===
Debug mode: Processing middle block: [50, 40]
Processing 1 grid pairs. 100 by 80
Debug mode: Initializing ImageJ for visualization...
Processing grid coord: 53 34
Debug mode: Displaying input data...
Debug mode: Displaying derivative...
Debug mode: Displaying derivative converted...
cost: [0,0,0] [2047,2047,127]
Debug mode: Skipping N5 write operations
Debug mode: Skipping downsampling and surface fitting
```

### ImageJ Windows

1. **Input [X,Y]**: The raw input data
   - Check for proper data loading
   - Verify intensity ranges (should not be all black or all white)
   - Look for missing data or artifacts

2. **Mask [X,Y]**: Binary mask showing valid data regions
   - White (255) = valid data
   - Black (0) = no data / excluded regions

3. **Derivative [X,Y]**: Z-direction intensity derivative
   - Shows bright-to-dark transitions (tissue boundaries)
   - Lower values = stronger transitions
   - Should show clear bands where resin meets tissue

4. **Derivative Converted [X,Y]**: Scaled derivative values
   - Enhanced contrast for better visualization
   - Values scaled by factor of 4

5. **Derivative Smoothed [X,Y]**: Gaussian-smoothed result (if --smoothCost)
   - Reduces noise in Z direction
   - Sigma = 1.0 in Z only

6. **Cost Block [X,Y]**: Final cost function
   - Lower cost (darker) = more likely surface location
   - Higher cost (brighter) = less likely surface location
   - Should show clear minima at tissue boundaries

## Debugging Workflow

1. **Start with a problematic block**
   ```bash
   --debugMode --debugBlockX <X> --debugBlockY <Y>
   ```
   ImageJ windows will automatically appear showing all intermediate steps.

2. **Check Input data**
   - Is the data loading correctly?
   - Are there missing tiles or corrupted blocks?
   - Is the intensity range reasonable?

3. **Check Derivative**
   - Are tissue boundaries visible as dark bands?
   - Is the derivative too noisy?
   - Are there false positives from imaging artifacts?

4. **Check Mask (if used)**
   - Is valid data properly masked?
   - Are boundary regions handled correctly?

5. **Check Final Cost**
   - Do cost minima align with tissue boundaries?
   - Are there spurious minima from artifacts?
   - Is the cost range appropriate (0-255)?

6. **Experiment with parameters**
   - Try `--median` to reduce noise in input
   - Try `--smoothCost` to smooth in Z
   - Adjust `--topLayerCost` and `--bottomLayerCost` for boundary handling
   - Modify `--costSteps` for different downsampling

## Parameter Recommendations

### For noisy data
```bash
--median --smoothCost
```

### For high-quality data
```bash
# No filtering needed
```

### For boundary artifacts
```bash
--topLayerCost 105 --bottomLayerCost 230
```
Adjust these values to control surface detection at volume boundaries. Lower top cost encourages top surface detection; higher bottom cost discourages bottom surface detection.

## Common Issues

### Issue: Cost function is uniform (all same value)
- Check that input data is not empty or constant
- Verify mask is not excluding all data
- Check derivative range - may need different scaling

### Issue: False boundaries detected
- Try `--median` to reduce noise
- Try `--smoothCost` for smoother transitions
- Check for imaging artifacts in input

### Issue: True boundaries not detected
- Check derivative values - may be too weak
- Verify intensity difference between tissue and resin
- Consider adjusting derivative scaling factor

### Issue: ImageJ windows not appearing
- ImageJ windows appear automatically in debug mode
- Make sure X11 forwarding is enabled if running over SSH
- Or run locally instead of on cluster
- Check that ImageJ is in classpath

## Performance Notes

- Debug mode should only be run locally or with X11 forwarding (for ImageJ display)
- Processing a single block typically takes seconds to minutes depending on size
- Default mode processes just one block (the middle one)
- ImageJ windows always appear in debug mode

## Reverting to Full Processing

Simply remove all `--debug*` flags:
```bash
# Full processing
/path/to/spark-submit \
  --class org.janelia.saalfeldlab.hotknife.SparkComputeCostMultiSem \
  hot-knife.jar \
  --inputN5Path /path/to/dataset.n5 \
  --outputN5Path /path/to/output.n5 \
  --inputN5Group /raw/s0 \
  --costN5Group /cost
```

## Branch Information

- **Branch**: `cloud-cost-debug`
- **Base**: `cloud`
- **Changes**:
  - Added `--debugMode`, `--debugBlockX`, `--debugBlockY` options
  - Modified grid coordinate generation to support selective block processing
  - Added automatic ImageJ visualization at key processing stages
  - Disabled all N5 writing operations in debug mode
  - All debug-related console output prefixed with "Debug mode:"
  - Replaced `--outOfBoundsValue` with `--topLayerCost` and `--bottomLayerCost` for finer boundary control
  - Changed boundary extension from `extendValue` to `extendBorder`
  - Added image inversion (255 - value) for input data
  - Updated `processColumn` and `processColumnAlongAxis` signatures

## Building

```bash
mvn clean package
```

## See Also

- `SparkComputeCost.java` - FIB-SEM version (different coordinate system)
- `SparkSurfaceFit.java` - Surface fitting using cost functions
- `DagmarCost.java` - Alternative cost function implementation
