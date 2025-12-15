package org.janelia.saalfeldlab.hotknife;

import picocli.CommandLine;

public class SparkExportFlattenedVolumeTest {

    public static void main(final String... args) {

        System.setProperty( "spark.master", "local[" + Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 ) + "]" );

        final String[] testArgs = {
                "--n5RawPath", "gs://janelia-spark-test/hess_wafers_60_61_export",
                "--n5RawDataset", "/render/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s0",
                "--n5FieldPath", "gs://janelia-spark-test/hess_wafers_60_61_export",
                "--n5FieldGroup", "/heightfields_b250_smd_p1_p1/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1",
                "--n5OutputPath", "gs://janelia-spark-test/hess_wafers_60_61_export",
                "--n5OutDataset", "/flat/w61_serial_070_to_079/w61_s079_r00/raw_debug_interactive",
                "--padding", "3",
                "--blockSize", "128,128,128",
                "--multiSem",
                "--debugMode", "INTERACTIVE"
        };

        final CommandLine cmd = new CommandLine(new SparkExportFlattenedVolume());
        cmd.execute(testArgs);
    }

}
