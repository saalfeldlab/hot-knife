package org.janelia.saalfeldlab.hotknife;

public class SparkExportFlattenedVolumeMultiSEMBatchTest {

    public static void main(final String... args)
            throws Exception {

        System.setProperty( "spark.master", "local[" + Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 ) + "]" );

        final String[] testArgs = {
                "--n5RootPath", "gs://janelia-spark-test/hess_wafers_60_61_export",
                "--padding", "3",
                "--blockSize", "128,128,128",
                "--raw", "w61_s079_r00",
                "--debugMode", "INTERACTIVE"
        };

        SparkExportFlattenedVolumeMultiSEMBatch.main(testArgs);
    }

}
