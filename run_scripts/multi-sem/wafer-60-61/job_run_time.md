# Job Run Time Estimate: hot-knife Steps for w61 Serial Slabs 070 to 199

Estimated time to run serial slabs 070 to 199 (130 r00 and 110 r01 stacks) through the per-stack
hot-knife steps, `51_gen_cost_and_heightfields_n5_batch.sh` to `63_gen_face_batch.sh`, on Dataproc
Serverless with a quota of about 3,000 cores.  Estimated 2026-11-18.

## Summary

| Approach                                                          | Typical                  | Range      |
|-------------------------------------------------------------------|--------------------------|------------|
| **Start 61 to 63 jobs on the cores 51 leaves free**               | **~83 h (3 days 11 h)**  | 80 to 90 h |
| Stage by stage (finish a step for all stacks before the next)     | ~94 h (3 days 22 h)      | 90 to 100 h|
| Lower limit (all 3,000 cores busy the whole time)                 | ~80 h (3 days 8 h)       |            |

Overlapping the steps saves about 11 h.  51 can only run about 8 jobs at once (see below), which
leaves about 1,270 cores idle while it runs.  Those cores can run seven 61, 62, or 63 jobs for stacks
whose heightfields are done.  After that, all four steps are limited by cores, so no ordering does much
better than the 80 h lower limit.

The surface alignment passes and the final export are listed separately below, because there are no
w61 run times on Google for most of them.

The estimates do not include time to launch jobs, review, overnight gaps, failures, or reruns.
**If the heightfield fix being worked on means rerunning 51, the 61 to 63 steps for those stacks have to be
rerun too.**

## Stage Breakdown

These are the stage times when each step runs by itself.

| Step                            | Jobs at once (limit)          | Time per r00 stack (from script notes)  | Job hours (r00 + r01) | Stage time |
|---------------------------------|-------------------------------|-----------------------------------------|-----------------------|------------|
| 51 cost and heightfields, 50 executors | 8 (rateLimitExceeded at 9: 216 cores per job) | 1.35 to 1.5 h (1 h 21 min to 1 h 30 min) | 244 | ~30 h (29 to 33 h) |
| 61 flatten, 40 executors        | 18 (cores: 164 per job)       | 1 h 38 min                              | 284                   | ~16 h      |
| 62 masked CLAHE, 40 executors   | 18 (cores)                    | 2 h 47 min                              | 484                   | ~27 h      |
| 63 faces (both), 40 executors   | 18 (cores)                    | 2 h 9 min                               | 374                   | ~21 h      |
| **Compute total**               |                               |                                         | **1,386**             | **~94 h**  |

62 is the largest step (about a third of the core hours), then 63.  At 40 executors, 61 to 63 together
need about 187,000 core hours, compared with about 53,000 for 51.

| Optional step                   | Jobs at once                  | Time per stack                          | Stage time |
|---------------------------------|-------------------------------|-----------------------------------------|------------|
| 52 heightfield surface export (review only), 10 executors | many (44 cores per job) | ~6 min (s0 of w61 s070 r00) | 1 to 2 h alongside the other steps |

## Assumptions

- **Run times** come from the notes in each script's usage text:
  - 51: w61 s093 r00 (89 z layers) took 1 h 21 min with 50 executors, s079 r00 (82 z layers) took
    1 h 30 min with 60 executors, and s101 plus s102 r00 took 2 h 54 min together with 75 executors.
    The estimate uses 1.4 h per r00 stack at 50 executors (1.35 to 1.5 h for the range).
  - 61, 62, and 63 each have one r00 timing at 40 executors.  For 62, two stacks with 80 executors
    took 4 h 1 min, so doubling the executors only partly helped and used more DCU time.
  - 52 is from one run on 2026-11-17, in which the s0 level took about 5.5 minutes.
- **r01 stacks** are assumed to take 0.4 times as long as r00 stacks.  That is the ratio of the
  r01 and r00 pixel export times (2.5 to 3.5 h and 7 to 9 h) in the EM_recon_pipeline estimate.
  None of these hot-knife steps have an r01 timing yet.
- **Cores per job** are 4 per executor plus the driver.  51 sets 16 driver cores, so a 50 executor
  job uses 216 cores.  61 to 63 don't set the driver cores, so they use the Dataproc Serverless
  default of 4, which makes 164 cores for 40 executors.
- **51 jobs at once:** the 51 script notes that when 9 jobs with 50 executors ran at once, 2 of them
  eventually failed with rateLimitExceeded errors.  So the estimate allows 8 at once (1,728 cores)
  rather than the 13 that the cores would allow.
- **61 to 63 jobs at once:** these are limited only by cores (18 jobs of 164 cores).  It is not known
  whether they hit the same rateLimitExceeded errors.  If they also had to stay at 8 jobs at once,
  61 to 63 would take about 143 h instead of 64 h.
- **Batch sizes** are assumed to split the stacks evenly enough that every job slot stays busy.
  With 51's default `--batch-size 10`, a job of 10 r00 stacks takes about 14 h, which fits in the
  24 h batch TTL.
- **Order of the steps** for each stack: 51 writes the cost and heightfields, 61 flattens the pixel
  data with them, 62 runs CLAHE on the flattened data (with downsampling), and 63 extracts the top
  and bottom faces from the CLAHE output.

## Why Overlapping Helps (a Little)

- **51 can't use all the cores.**  The rate limit keeps it to about 8 jobs (1,728 cores), so
  stage by stage, about 1,270 cores sit idle for its 30 h.
- **Overlapping fills that gap.**  Seven 164-core jobs of 61 to 63 fit in the idle cores once the first
  stacks have heightfields (after about 1.5 h).  They get through about 200 of the 1,140 job hours of
  61 to 63 while 51 runs, and the remaining 61 to 63 work then takes about 52 h on all the cores.
- **After that, cores are the limit.**  The four steps need about 240,000 core hours together, which
  takes at least 80 h on 3,000 cores however they are ordered.

## Other Options Considered

- **Fewer executors per 51 job.**  The 51 notes show 4 and 6 executor jobs using about 51 to 52
  executor hours per stack (12 h 40 min and 8 h 44 min), compared with about 68 at 50 executors and
  90 at 60.  So small jobs use about 25% fewer core hours.  They would need dozens of jobs at once,
  though, and the rateLimitExceeded errors at 9 jobs suggest that may not work.  At 6 executors, a
  job also has to keep to 2 r00 stacks to stay inside the 24 h TTL.
- **More executors for 62.**  80 executors finished two stacks in 4 h 1 min (about 2 h per stack, down
  from 2 h 47 min), but used more DCU time.  That only helps if cores are spare, which they aren't
  after 51 finishes.
- **63 one face at a time.**  The 63 notes say top or bottom alone takes about half the time of both, so
  splitting the faces doesn't change the total.  It only helps if one face is needed sooner.

## Surface Alignment and Export (Not Estimated)

These steps work on a whole region's slab series and start once its faces are done.  r00 and r01 are
separate series, so they can run at the same time.

| Step                                   | Default executors or nodes         | Known run time                                                   |
|----------------------------------------|------------------------------------|------------------------------------------------------------------|
| 73 surface align pass 0                | 10 executors                       | 7 min for 90 w61 r00 slabs (70 to 159), 4 min for 4 slabs        |
| 74 passes 1 to 12 (Google)             | 20 to 70 executors, by pass        | none recorded ("??" in the script notes)                         |
| 74 passes 1 to 12 (Janelia)            | 60 to 210 nodes, by pass           | pass 4 took 48 min for 5 w61 r00 slabs with 150 nodes             |
| 76 export align view (Janelia)         | 10 nodes                           | 20 min for 90 slabs (180 faces) at scale index 4                 |
| 91 export aligned slab series (Janelia)| 200 nodes                          | 66 min for run_20251219_110000/pass03-sofima-fix2-full           |
| 99 downsample final (Janelia)          | 50 nodes                           | 8 min for run_20251219_110000/pass03-sofima-fix2-full            |

For a rough idea only: the wafer 53 production run (402 slabs, in the hot-knife CLAUDE.md) took
2 to 3 h for each of passes 1 to 3, 4 to 6 h for each of passes 4 to 7, 3 to 5 h for each of
passes 8 to 11, and about 15 h for pass 12.  That is about 50 to 70 h of passes in a row, before the
review and editing between passes.  How that compares with the w61 series (130 r00 and 110 r01 slabs, on
Google rather than the Janelia cluster) is not known, so time the first few w61 passes before planning
around these numbers.

## Not Included

- the alignment passes and final export (see above)
- `01_copy_jar.sh`, reviewing heightfields in neuroglancer or PaintHeightField, and fixing heightfields
- reruns of 51 to 63 if the heightfields change
- time to launch jobs, overnight gaps, failures, and reruns
