# Hot-Knife Codebase Summary

## Project Overview
Hot-knife is an Apache Spark-based pipeline for processing large-scale 3D electron microscopy (EM) image volumes from FIB-SEM (Focused Ion Beam Scanning Electron Microscopy) and Multi-SEM slab series. The system handles multi-TB datasets by distributing processing across Spark clusters.

## Core Workflow

### Six-Stage Processing Pipeline

1. **Data Import**
   - Convert TIFF series to N5/ZARR format
   - Generate multi-scale pyramids (s0-s8)
   - Optional cropping and preprocessing

2. **Preprocessing**
   - Intensity normalization (layer-wise)
   - Multi-scale pyramid generation for efficient processing

3. **Surface Detection & Volume Flattening**
   - Compute cost functions for tissue/resin boundaries
   - Fit surfaces using MinCostZSurface graph-cut algorithm
   - Multi-scale hierarchical refinement (coarse to fine)
   - Output: Height fields (top and bottom surfaces)
   - Optional: Manual correction with PaintHeightField
   - CLAHE contrast enhancement (masked by height fields)
   - Flatten volumes using height fields (FlattenTransform)

4. **Face Extraction**
   - Extract 2D surface faces (top/bottom) from flattened 3D volumes
   - Generate multi-scale pyramids for extracted faces
   - Run twice: once for top face, once for bottom face

5. **Multi-Pass Alignment** (12 passes + initial global pass)
   - **Pass 0**: Global affine alignment (SparkAlignAffineGlobal)
   - **Passes 1-7**: SIFT-based alignment with averaging (SparkPairAlignSIFTAverage)
     - Progressive coarse-to-fine refinement (s4 → s2)
     - Grid-based feature matching between adjacent slab pairs
   - **Passes 8-12**: Optical flow-based dense alignment (SparkPairAlignFlow)
     - Dense flow field computation with smoothness regularization
     - Finest scale refinement (s5 → s1)
   - Output: Transform position fields for each pass
   - **Quality Control (after each pass)**:
     - ViewAlignment: Visualize alignment results
     - ViewAlignmentPlayground13: Interactively edit/correct transforms if needed
     - Each edited pass saved to new alignment group (e.g., pass02_edit)

6. **Volume Assembly**
   - ViewAlignedSlabSeries: Virtual rendering to validate alignment and adjust slab spacing
   - SparkExportAlignedSlabSeries: Export final unified 3D volume
   - Optional: Downsample final volume for visualization

## Key Classes by Function

### Surface Detection & Extraction
- **SparkComputeCost** / **SparkComputeCostMultiSem**: Compute cost functions using DagmarCost algorithm
  - Gradient detection and resin band detection
  - Multi-scale Gaussian filtering and DoG
  - Processes along axis 0, 2, or both

- **SparkSurfaceFit**: Detect surfaces using MinCostZSurface
  - Multi-scale approach (s8 → s1)
  - Constraint-based: maxDeltaZ (slope), minDistance/maxDistance (thickness)
  - Outputs height fields (min/max) as 2D arrays

- **SparkExportFlattenedVolume** / **SparkExportFlattenedVolumeMultiSEMBatch**: Flatten volumes
  - Applies FlattenTransform using height fields
  - Warps between curved surfaces and flat space
  - Block-wise processing for memory efficiency

### Alignment (12-Pass Multi-Resolution Pipeline)
- **SparkAlignAffineGlobal** (Pass 0): Global affine alignment across all slab faces
  - Initial coarse alignment using RANSAC
  - Creates baseline transform for all slabs
  - Fast execution: ~5 minutes for 402 slabs

- **SparkPairAlignSIFTAverage** (Passes 1-7): SIFT-based alignment with averaging
  - Grid-based SIFT feature matching between adjacent slab pairs
  - Computes average transform across overlapping grid cells
  - Progressive refinement: coarse → fine scales (s4 → s2)
  - Regularized affine models (rigid + affine with lambda interpolation)
  - Passes 1-3: Coarse alignment (scaleIndex 4→3, stepSize 512-400)
  - Passes 4-7: Medium refinement (scaleIndex 2, stepSize 400-512)

- **SparkPairAlignFlow** (Passes 8-12): Optical flow-based dense alignment
  - Dense flow field computation for fine-scale refinement
  - Smoothness regularization (sigma parameter)
  - Progressive scale refinement: s5 → s1 (coarse to finest)
  - Passes 8-10: Flow alignment (scaleIndex 5→3)
  - Passes 11-12: Final fine alignment (scaleIndex 2→1)
  - Computationally intensive: pass 12 can take 15+ hours for large datasets

- **SparkPairAlignSIFT**: Legacy single-pass SIFT alignment (used for simpler workflows)
  - Grid-based feature detection
  - Exports position field transforms

- **SparkSeriesAlignSIFT**: Sequential alignment of slab series

### Interactive Visualization & Quality Control
- **ViewAlignment**: View alignment results after each pass
  - Visualizes aligned face pairs with applied transforms
  - Multi-scale visualization (specify scaleIndex for performance)
  - Options: `--noVirtual` (precompute transforms), `--ignoreTransforms` (compare without alignment)
  - Can view multiple alignment passes simultaneously for comparison
  - Used for quality control after each of the 12 alignment passes

- **ViewAlignmentPlayground13**: Interactive alignment editor
  - Load two adjacent face transforms from any alignment pass
  - Apply incremental transforms interactively with keyboard shortcuts:
    - **Enter**: Apply current incremental transform
    - **Escape**: Cancel incremental transform
    - **Ctrl/Cmd+Z**: Undo
    - **Ctrl/Cmd+Shift+Z**: Redo
  - Save edited transforms to new alignment pass output group
  - Allows manual correction of alignment errors between passes
  - Real-time preview of transform effects

- **ViewAlignedSlabSeries**: Virtual rendering of complete aligned volume
  - Renders entire multi-slab volume with all transforms applied
  - Takes alignment group and per-slab top/bottom offsets as input
  - Optional contrast normalization (`--normalizeContrast`)
  - Optional intensity inversion (`--invert`)
  - Interactive adjustment of slab spacing and positioning
  - Used to validate final alignment before running SparkExportAlignedSlabSeries
  - Enables manual tuning of top/bot offsets for optimal slab transitions

### Data Conversion
- **SparkConvertTiffSeriesToN5**: TIFF series → N5
- **SparkConvertBlockedTiffSeriestoN5**: Blocked TIFF → N5
- **SparkConvertRenderStackToN5**: Import from Render service
- **SparkConvertCATMAIDStackToN5**: Import from CATMAID
- **SparkDownsampleZarr**: Multi-scale pyramid generation for ZARR

### Volume Export & Face Extraction
- **SparkExportFlattenedVolume** / **SparkExportFlattenedVolumeMultiSEMBatch**: Flatten curved tissue slabs using height fields
  - Applies FlattenTransform to create 3D flattened volumes
  - Uses min/max height fields to warp between curved and flat space
  - Outputs 3D N5 datasets for each slab

- **SparkGenerateFaceScaleSpace** / **SparkGenerateFaceScaleSpaceMultiSEMBatch**: Extract 2D surface faces and generate scale spaces
  - Takes flattened 3D volume as input
  - Extracts top (min) and bottom (max) 2D surface faces using hyperslicing
  - Generates multi-scale pyramids (s1-s9) for each face
  - Use `--min` and `--size` parameters to specify face extraction (e.g., `--size=10000,20000,1` for single slice)
  - Run twice: once for top face, once for bottom face
  - Output used for SIFT-based surface alignment

- **SparkExportAlignedSlabSeries**: Export aligned slab series as unified volume
  - Assembles multiple aligned slabs into final seamless 3D volume

### Additional Visualization Tools
- **ViewN5**: General N5 dataset viewer
- **ViewFlattenedSlab**: Visualize flattened volumes
- **PaintHeightField**: Interactive height field editor (see dedicated section below)

### Image Processing & Contrast Enhancement
- **SparkNormalizeLayerIntensityN5**: Layer-wise intensity normalization
- **SparkPixelNormalizeN5**: Pixel-level normalization

- **SparkMaskedCLAHEMultiSEM** / **SparkMaskedCLAHEMultiSEMBatch**: Masked contrast-limited adaptive histogram equalization
  - Applied AFTER surface detection and BEFORE volume flattening
  - Uses height fields (min/max) to create tissue mask
  - Only processes voxels within tissue boundaries (between surfaces)
  - Improves local contrast while avoiding resin regions
  - Parameters:
    - `--blockFactorXY`: Compute block size multiplier in XY (default: 8)
    - `--blockFactorZ`: Compute block size multiplier in Z (default: 1)
  - Batch version processes multiple stacks sequentially
  - Input: Normalized raw data (`.../s0`)
  - Output: CLAHE-enhanced data (`..._norm-layer-clahe/s0`)
  - Essential for improving alignment quality by enhancing tissue features

## Key Data Structures

### Height Fields
- 2D arrays storing z-coordinates (surface position) for each (x,y) location
- Stored as FloatType images in N5 format
- Two fields: **min** (top surface) and **max** (bottom surface)
- Processed at multiple scales with interpolation
- Attributes: avg (weighted average), downsamplingFactors

### Cost Functions
- 3D volumes (x, y, z) with UnsignedByteType values
- Lower cost = higher probability of surface location
- Generated by DagmarCost algorithm:
  - Gaussian filtering (sigma 3 and 8)
  - Gradient magnitude computation
  - Resin band detection
  - Slope correction

### Transform Representations
- **Position Fields**: Coordinate lookup tables stored as N5 datasets
  - Format: (n+1)-dimensional array, last dimension = coordinate components
  - Metadata: boundsMin, boundsMax, scale
  - Double precision (float64)

- **FlattenTransform**: Warps between curved surfaces
  - Uses min/max height fields
  - Invertible transformation

## Key Algorithms

### MinCostZSurface
- Graph-cut based surface detection algorithm
- Constraints:
  - **maxDz**: Maximum surface slope between adjacent pixels
  - **minDistance/maxDistance**: Enforce thickness constraints between surfaces
  - Supports multiple surfaces with no-crossing constraints
- Finds globally optimal surface given constraints

### DagmarCost
- Cost function computation for tissue/resin boundary detection
- Pipeline:
  1. Multi-scale Gaussian filtering
  2. Gradient magnitude computation (tissue boundaries = high gradient)
  3. Resin band detection (uniform regions = low cost)
  4. Slope correction for oblique boundaries
  5. Inversion (high gradient → low cost)

### Multi-Scale Processing
- Hierarchical coarse-to-fine refinement (s8 → s7 → ... → s1 → s0)
- Each scale provides initialization for finer scale
- Reduces computational cost and improves robustness
- Different parameters (maxDeltaZ) per scale

## N5/ZARR Storage Format

### Dataset Organization
```
/dataset/
  s0/           # Full resolution
  s1/           # 2x downsampled
  s2/           # 4x downsampled
  ...
  s8/           # 256x downsampled
```

### Attributes (attributes.json)
- **dimensions**: [x, y, z] array size
- **blockSize**: Chunk sizes (typically 128³ or 256³)
- **downsamplingFactors**: Scale factors per dimension
- **dataType**: UINT8, FLOAT32, FLOAT64
- **compression**: GzipCompression

### Coordinate Systems
- **FIB-SEM**: Requires axis permutation (Views.permute(image, 1, 2))
- **Multi-SEM**: Direct axis order (no permutation)
- Column-major ordering for coordinates

### N5Util Helper
- Factory pattern using **N5Factory** for creating readers/writers
- Auto-detects N5 vs ZARR (.zarr extension or .zarray/.zgroup files)
- Transparent handling of both formats

## Package Organization

### `/org/janelia/saalfeldlab/hotknife/`
- Main package with Spark applications (37 Spark* classes)
- Transform classes: FlattenTransform, HeightFieldTransform, DeformationFieldTransform

### `/util/`
- **Transform**: Transform utilities, position fields, scaling
- **Grid**: Grid block generation for parallelization
- **Align**: Alignment algorithms (SIFT matching)
- **Show**: Visualization utilities for BigDataViewer
- **N5Util**: N5 factory helpers

### `/cost/`
- **DagmarCost**: Cost function computation
- **CostUtils**: Cost function utilities
- **PreFilter**: Preprocessing filters

### `/ops/`
- Image processing operators: CLLCN (contrast normalization), Gauss, gradients
- Lazy evaluation with cell loaders

### `/brain/`
- Specialized workflows for brain-specific processing
- VNC alignment and stitching

### `/tobi/`
- Interactive tools and GUI components
- Surface pyramid visualization
- Alignment playground
- Parameter editors

### `/tools/`
- Utility classes: Height field controllers, mipmap sources

## Architectural Patterns

### 1. Scale-Space Processing
- Coarse-to-fine hierarchical approach
- Initialize fine scales from coarser results
- Reduces computation and improves robustness

### 2. Lazy Evaluation
- ImgLib2 Views and transforms composed without copying data
- Computation happens on-demand during iteration
- Memory efficient for TB-scale volumes

### 3. Block-Wise Streaming
- Volumes divided into blocks/chunks
- Process blocks independently with optional overlap
- Never load entire volume into memory
- N5 format enables efficient random access

### 4. Distributed Computation
- Apache Spark RDDs distribute work across cluster
- Serializable operations for network transfer
- Grid-based parallelization for spatial decomposition
- Concurrent N5 access (multiple workers read/write different blocks)

### 5. Transform Composition
- Chain multiple transforms: affine + position field + flatten
- Applied lazily without materializing intermediate results
- Invertible transforms enable bidirectional mapping

## Technology Stack

- **Apache Spark 3.4.1**: Distributed computing framework
- **ImgLib2**: Type-generic image processing library
- **N5/ZARR**: Chunked n-dimensional array storage formats
- **BigDataViewer**: Interactive 3D visualization with lazy loading
- **mpicbg**: Model-based image alignment library
- **MinCostZSurface**: Graph-cut surface detection algorithm
- **Java**: Primary implementation language

## Processing Parameters

### Common Parameters
- **blockSize**: N5 chunk size (128³ or 256³)
- **maxDeltaZ**: Maximum surface slope constraint (pixels)
- **minDistance/maxDistance**: Surface separation constraints (pixels)
- **bandSize**: Resin band detection window size
- **minGradient**: Minimum gradient threshold for tissue boundaries
- **maxSlope**: Maximum expected tissue slope
- **scales**: Multi-scale pyramid levels (s0-s8)

### Cost Computation Parameters (SparkComputeCostMultiSem)
- **bandSize**: Resin band detection window size (e.g., 200)
- **minGradient**: Minimum gradient threshold for tissue boundaries (e.g., 20)
- **maxSlope**: Maximum expected tissue slope (e.g., 25)
- **bottomLayerCost**: Cost value for bottom layer pixels (e.g., 250 for fly MultiSEM)
- **surfaceInitMaxDeltaZ**: Initial max slope for surface detection (e.g., 0.1 for fly MultiSEM)
- **surfaceMaxDeltaZ**: Max slope during surface detection (e.g., 0.1 for fly MultiSEM)
- **finalMaxDeltaZ**: Final max slope for refinement (e.g., 0.2 for fly MultiSEM)
- **axis**: Which surfaces to detect (0=top, 2=bottom, or both)

### Alignment Parameters

**12-Pass Alignment Pipeline** (used for large Multi-SEM datasets):

| Pass | Class | scaleIndex | stepSize | lambdaFilter | lambdaModel | maxEpsilon | sigma | Purpose |
|------|-------|------------|----------|--------------|-------------|------------|-------|---------|
| 0 | SparkAlignAffineGlobal | 5 | - | - | - | 320 | - | Initial global affine (RANSAC) |
| 1 | SparkPairAlignSIFTAverage | 4 | 512 | 0.01 | 0.01 | 100 | - | Coarse SIFT alignment |
| 2 | SparkPairAlignSIFTAverage | 4 | 400 | 0.1 | 0.01 | 50 | - | Coarse SIFT refinement |
| 3 | SparkPairAlignSIFTAverage | 3 | 512 | 0.1 | 0.01 | 40 | - | Medium SIFT alignment |
| 4 | SparkPairAlignSIFTAverage | 2 | 400 | 0.1 | 0.01 | 20 | - | Fine SIFT alignment |
| 5-7 | SparkPairAlignSIFTAverage | 2 | 512 | 0.25 | 0.01 | 20 | - | Fine SIFT refinement (3 passes) |
| 8 | SparkPairAlignFlow | 5 | 256 | - | - | 3 | 30 | Coarse flow alignment |
| 9 | SparkPairAlignFlow | 4 | 256 | - | - | 3 | 30 | Medium flow alignment |
| 10 | SparkPairAlignFlow | 3 | 256 | - | - | 5 | 30 | Medium-fine flow alignment |
| 11 | SparkPairAlignFlow | 2 | 256 | - | - | 3 | 30 | Fine flow alignment |
| 12 | SparkPairAlignFlow | 1 | 256 | - | - | 3 | 30 | Finest flow alignment |

**Parameter Descriptions:**
- **scaleIndex**: Resolution level (4 = 1/16 resolution, 1 = 1/2 resolution, 0 = full resolution)
- **stepSize**: Grid cell size in pixels for alignment blocks
- **lambdaFilter**: Rigid-to-affine regularization for RANSAC filtering (0=rigid, 1=affine)
- **lambdaModel**: Rigid-to-affine regularization for model fitting (0=rigid, 1=affine)
- **maxEpsilon**: Maximum residual error threshold in world pixels
- **sigma**: Smoothness constraint for optical flow (larger = smoother transforms)

**Typical Runtime** (402 slabs, 7 mFOVs each):
- Pass 0: ~5 min, Passes 1-3: 2-3 hrs each, Passes 4-7: 4-6 hrs each
- Passes 8-11: 3-5 hrs each, Pass 12: ~15 hrs (most computationally intensive)

## Batch Processing Parameters (Wafer-53-Center7 Production Run)

### Masked CLAHE Contrast Enhancement (SparkMaskedCLAHEMultiSEMBatch)
**Script**: `64_spark_masked_clahe_batch.sh`

**Parameters**:
```bash
--n5PathInput=<n5_path>
--datasetCsv=<batch_csv>
--blockFactorXY=32       # compute blocks are 32× larger than storage blocks in XY
--blockFactorZ=1         # compute blocks same size as storage blocks in Z
--overwrite              # overwrite existing output datasets
```

**Batch Configuration**:
- 35 slabs per batch (5-10 hours per batch)
- 402 total slabs → 12 batch CSV files
- 15 nodes with 11 cores each (2 executors × 5 cores + 1 overhead core)
- Runtime: ~233 hours max per batch

**CSV Format** (input_dataset, output_dataset, heightfield_max):
```
/render/<slab>_norm-layer/s0,/render/<slab>_norm-layer-clahe/s0,/heightfields_fix/<slab>/max
```

**Processing Details**:
- Input: Layer-normalized data (`..._norm-layer/s0`)
- Mask: Max height field (`/heightfields_fix/<slab>/max`) defines tissue boundary
- Output: CLAHE-enhanced data (`..._norm-layer-clahe/s0`)
- Only voxels between min and max height fields are processed
- Large blockFactorXY (32) improves CLAHE quality by using larger context windows

### Volume Flattening (SparkExportFlattenedVolumeMultiSEMBatch)
**Script**: `71_spark_export_flat_batch.sh`

**Parameters**:
```bash
--n5RootPath=<n5_path>
--datasetCsv=<batch_csv>
--padding=3              # padding beyond height field min/max
--blockSize=128,128,64   # output block size (z is smaller for efficiency)
--downsample             # automatically generate s1-s9 downsampling pyramids
```

**Batch Configuration**:
- 31 slabs per batch (to balance runtime ~5-7 hours per batch)
- 402 total slabs → 13 batch CSV files
- 20 nodes with 11 cores each (2 executors × 5 cores + 1 overhead core)
- Runtime: ~233 hours max per batch

**CSV Format** (raw_dataset, heightfield_dataset, output_dataset):
```
/render/<slab>_norm-layer-clahe/s0,/heightfields_fix/<slab>,/flat_clahe/<slab>/raw/s0
```

**Input/Output Structure**:
- Input: CLAHE-filtered normalized data (`..._norm-layer-clahe/s0`)
- Height fields: Fixed/corrected height fields (`/heightfields_fix/<slab>`)
- Output: Flattened volume with auto-downsampling (`/flat_clahe/<slab>/raw/s0-s9`)

### Face Extraction (SparkGenerateFaceScaleSpaceMultiSEMBatch)
**Script**: `72_spark_extract_face_batch.sh`

**Parameters**:
```bash
--n5Path=<n5_path>
--datasetCsv=<batch_csv>
--blockSize=1024,1024    # 2D block size for face images
--invert                 # invert intensity (needed for old MultiSEM data)
```

**Batch Configuration**:
- Same batch structure: 31 slabs per batch, 13 total batches
- Surface depth: 4 pixels (extracts 4-pixel thick slice from top/bottom)
- Face naming: `top4i` (top face, 4 pixels, inverted) and `bot4i` (bottom face)

**CSV Format** (input_3d_volume, output_face, min_z, size_z):
```
/flat_clahe/<slab>/raw/s0,/flat_clahe/<slab>/top4i,4,<surface_size>
/flat_clahe/<slab>/raw/s0,/flat_clahe/<slab>/bot4i,-4,-<surface_size>
```

**Surface Size Calculation**:
- Read `avg` value from height field max attributes.json
- `surface_size = floor(avg) - 2`
- Ensures face is positioned at correct depth within flattened volume

**Face Extraction Details**:
- **Top face** (`top4i`): Extracts from `min_z = 4` to `max_z = surface_size`
- **Bottom face** (`bot4i`): Extracts from `min_z = -4` to `max_z = -surface_size`
- Negative values indicate extraction from bottom of volume
- Both faces include multi-scale pyramids (s0-s9) for alignment
- Output: `/flat_clahe/<slab>/{top4i,bot4i}/face/s0-s9`

**Runtime Notes**:
- Similar compute requirements to flattening (20 nodes, 11 cores each)
- Each slab generates 2 face datasets (top + bottom)
- Faces used as input for subsequent 12-pass alignment workflow

## Typical Workflow Example

```bash
# 1. Convert TIFF to N5
SparkConvertTiffSeriesToN5 \
  --input /path/to/tiffs \
  --output /path/to/dataset.n5

# 2. Generate multi-scale pyramid
SparkGenerateFaceScaleSpace \
  --input /path/to/dataset.n5 \
  --scales 8

# 3. Compute cost function
SparkComputeCost \
  --input /path/to/dataset.n5 \
  --output /path/to/cost.n5 \
  --bandSize 200 \
  --minGradient 20

# 4. Fit surfaces
SparkSurfaceFit \
  --cost /path/to/cost.n5 \
  --heightFields /path/to/heightfields.n5 \
  --maxDeltaZ 0.25,0.5,1.0,2.0 \
  --minDistance 100 \
  --maxDistance 500

# 5. Flatten volume
SparkExportFlattenedVolume \
  --input /path/to/dataset.n5 \
  --heightFields /path/to/heightfields.n5 \
  --output /path/to/flattened.n5

# 6. Align slabs
SparkPairAlignSIFT \
  --inputA /path/to/slabA.n5 \
  --inputB /path/to/slabB.n5 \
  --output /path/to/transform.n5

# 7. Export final volume
SparkExportAlignedSlabSeries \
  --inputs /path/to/slab*.n5 \
  --transforms /path/to/transform*.n5 \
  --output /path/to/final.n5
```

## Key Insights

1. **Petabyte-scale capability**: Architecture enables processing of enormous EM volumes through chunked storage, lazy evaluation, and distributed computing

2. **Multi-scale robustness**: Coarse-to-fine processing provides robust initialization and reduces computational cost by orders of magnitude

3. **Constraint-based surface detection**: MinCostZSurface enforces biological priors (tissue thickness, continuity) for reliable surface detection

4. **Modular pipeline**: Each stage can be run independently, inspected, and refined before proceeding

5. **Interactive refinement**: Visualization tools (ViewAlignedSlabSeries, ViewAlignment) enable manual correction where automated methods fail

6. **Format agnostic**: N5Util abstraction supports both N5 and ZARR formats transparently

7. **Grid-based parallelization**: Spatial decomposition enables efficient distributed processing with Spark

## Interactive Height Field Editing with PaintHeightField

### Overview
**PaintHeightField** is a BigDataViewer-based interactive tool for manually correcting and refining height fields produced by automated surface detection (SparkSurfaceFit). Essential for quality control before final volume assembly.

**Location**: `org.janelia.saalfeldlab.hotknife.tools.PaintHeightField`

### What It Does
- Loads multi-scale N5 raw images and 2D height field arrays
- Uses **HeightFieldTransform** to flatten curved tissue slabs in real-time
- Provides brush-based editing to manually correct surface detection errors
- Displays gradients to identify discontinuities and artifacts

### Key Capabilities

**1. Real-Time Flattening**
```java
// Transform: z_flat = z_curved - heightField(x,y) + offset
```
- Inverse transform applied for display (samples from curved raw data)
- Updates immediately as you edit height fields
- `offset` parameter positions target surface at z=0

**2. Interactive Editing Tools**
- **Push/Pull** (SPACE + mouse): Adjust height values ±0.1 × magnitude
- **Smooth** (Q + mouse): Gaussian smoothing with adjustable radius/sigma
- **Weighted Smooth** (W + mouse): Gradient-weighted smoothing (preserves edges)
- All tools use Gaussian brush masks for smooth modifications

**3. Visual Feedback**
- Flattened raw data view (multi-scale mipmaps)
- Current gradient (green) - updates with edits to show current quality
- Input gradient (red) - reference showing original issues
- Brush overlays indicate editing region and tool type

### Usage

**Command Line:**
```bash
java -cp hot-knife.jar org.janelia.saalfeldlab.hotknife.tools.PaintHeightField \
  --n5Path=gs://bucket/dataset.n5 \
  --n5Raw=/raw/s0 \
  --n5Field=/heightfields/s1/min \
  --n5FieldOutput=/heightfields_fixed/s1/min \
  --scale=4,4,4 \
  --offset=3 \
  --multiSem
```

**Key Parameters:**
- `--n5Path`: N5 container with raw data (supports gs:// for Google Cloud Storage)
- `--n5FieldPath`: N5 container with height field data (optional, defaults to n5Path)
- `--n5Raw`: Raw image dataset path (e.g., `/render/section/s0`)
- `--n5Field`: Input height field to edit (e.g., `/heightfields/s1/min`)
- `--n5FieldOutput`: Where to save edited height field (can overwrite input)
- `--scale`: Downsampling factors [x,y,z] matching height field resolution
- `--offset`: Z offset for target surface positioning (typically 0 for Multi-SEM)
- `--multiSem`: Flag for Multi-SEM data (no axis permutation)
- `--heightfieldOffset`: Optional [x,y,z] correction for DL-predicted height fields

**Cloud Storage Support:**
- Uses `N5Util.createN5Reader()` and `N5Util.createN5Writer()` for transparent cloud/local access
- Automatically handles gs:// URLs for Google Cloud Storage
- N5 API used for dataset existence checks instead of filesystem operations

**Keyboard Shortcuts:**
- **Ctrl+S**: Save edited height field to N5
- **Ctrl+U**: Undo (reload from disk)
- **Ctrl+C**: Go to z=0 surface
- **Ctrl+0**: Toggle z=0 line overlay
- **Ctrl+2**: Toggle scale display overlay
- **Ctrl+F/D/R/V**: Navigate (right/left/up/down)
- **SPACE + Left Mouse**: Push height field up
- **SPACE + Right Mouse**: Pull height field down
- **SPACE + scroll**: Adjust push/pull brush radius
- **Q + Left Mouse**: Apply Gaussian smoothing
- **Q + scroll**: Adjust smooth brush radius
- **Shift+Q + scroll**: Adjust smoothing sigma
- **W + Left Mouse**: Apply gradient-weighted smoothing
- **W + scroll**: Adjust weighted smooth brush radius
- **Shift+W + scroll**: Adjust weighted smoothing sigma
- **See**: PAINTHEIGHTFIELD_SHORTCUTS.md for complete reference

### Workflow Integration

**Typical Pipeline:**
1. **SparkComputeCostMultiSem** → Compute cost function
2. **SparkSurfaceFit** → Detect surfaces → Output height fields (`min` and `max`)
3. **PaintHeightField** → Manually correct height field errors (optional)
4. **SparkMaskedCLAHEMultiSEM** / **SparkMaskedCLAHEMultiSEMBatch** → Apply CLAHE filtering to volume
5. **SparkExportFlattenedVolume** / **SparkExportFlattenedVolumeMultiSEMBatch** → Use height fields to flatten slabs into 3D volumes
6. **SparkGenerateFaceScaleSpace** / **SparkGenerateFaceScaleSpaceMultiSEMBatch** → Extract 2D surface faces (top/bottom) and generate scale spaces (run twice: once for min, once for max)
7. **SparkPairAlignSIFT** → Align surfaces using extracted faces
8. **SparkExportAlignedSlabSeries** → Final assembly of aligned slabs

**When to Use:**
- Automated surface detection has artifacts or discontinuities
- High gradient regions in the height field gradient view
- Visual inspection shows misaligned surfaces in flattened view
- Before committing to expensive downstream processing

**Implementation Details:**
- Automatically uses ArrayImg for small height fields (≤2.1 billion elements) for better performance
- Switches to CellImg for large height fields that exceed Java array size limits
- Filters scale directories using regex pattern `s[0-9]+` to ignore non-scale subdirectories
- Height field scale attributes stored at parent directory level (e.g., `s1/attributes.json`, not `s1/min/`)
- All controllers accept `RandomAccessibleInterval<FloatType>` interface (compatible with both ArrayImg and CellImg)

**Note:** Height fields are typically stored at s1 resolution (downsampled 6x6x1 or 4x4x4). Process both `min` (top surface) and `max` (bottom surface) separately.

## Current Branch: cloud-cost-debug
- **Based on**: cloud branch
- **Purpose**: Debug SparkComputeCostMultiSem cost function issues
- **Key features**:
  - `--debugMode`: Process single block, no N5 writing, automatic ImageJ visualization
  - `--debugBlockX/Y`: Specify block coordinates (defaults to center block)
  - `--topLayerCost/--bottomLayerCost`: Independent control of boundary costs (replaces `--outOfBoundsValue`)
  - Image inversion and border extension for improved boundary handling
  - All debug output prefixed with "Debug mode:"
- **Recent updates**:
  - PaintHeightField updated with cloud storage support (N5Util)
  - Added CellImg support for large height fields exceeding ArrayImg limits
  - Fixed numScales calculation to filter non-scale directories
  - Added keyboard shortcuts documentation (PAINTHEIGHTFIELD_SHORTCUTS.md)
  - Updated PAINTHEIGHTFIELD_GUIDE.md with correct cloud paths and --n5FieldPath parameter
  - Fixed SparkSurfaceFit padding calculation to prevent NegativeArraySizeException in MinCostZSurface
- **See**: DEBUG_COST_MULTISEM.md for complete usage guide
