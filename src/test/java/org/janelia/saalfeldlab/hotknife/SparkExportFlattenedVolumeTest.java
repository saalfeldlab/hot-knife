package org.janelia.saalfeldlab.hotknife;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Scanner;

import picocli.CommandLine;

public class SparkExportFlattenedVolumeTest {

    public static void main(final String... args)
            throws IOException {

        System.setProperty( "spark.master", "local[" + Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 ) + "]" );

        final ZoneId easternTimeZone = ZoneId.of("America/New_York");
        final String debugSuffix = "_debug_" +
                                   java.time.LocalDateTime.now(easternTimeZone)
                                           .truncatedTo(ChronoUnit.SECONDS)
                                           .toString()
                                           .replace("T", "_")
                                           .replace(":", "")
                                           .replace("-", "");

        final SparkExportFlattenedVolume.DebugMode debugMode;
        try (Scanner scanner = new Scanner(System.in)) {
            debugMode = promptForEnumChoiceByNumber(scanner,
                                                    "Choose a debugMode:",
                                                    SparkExportFlattenedVolume.DebugMode.class);
        }

        final String desktopTestDir = System.getProperty("user.home") + "/Desktop/hotknife_test.n5";

        if (debugMode != SparkExportFlattenedVolume.DebugMode.INTERACTIVE) {
            //noinspection ResultOfMethodCallIgnored
            new File(desktopTestDir).mkdirs();
        }

        final String[] testArgs = {
                "--n5RawPath", "gs://janelia-spark-test/hess_wafers_60_61_export",
                "--n5RawDataset", "/render/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s0",
                "--n5FieldPath", "gs://janelia-spark-test/hess_wafers_60_61_export",
                "--n5FieldGroup", "/heightfields_b250_smd_p1_p1/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1",
                "--n5OutputPath", desktopTestDir,
                "--n5OutDataset", "/flat" + debugSuffix,
                "--padding", "3",
                "--blockSize", "128,128,128",
                "--multiSem",
                "--debugMode", debugMode.toString(),
        };

        final SparkExportFlattenedVolume exporter = new SparkExportFlattenedVolume();
        final CommandLine cmd = new CommandLine(exporter);
        cmd.execute(testArgs);
    }

    public static <E extends Enum<E>> E promptForEnumChoiceByNumber(Scanner scanner,
                                                                    String prompt,
                                                                    Class<E> enumType) {
        final E[] values = enumType.getEnumConstants();
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("Enum has no constants: " + enumType.getName());
        }

        while (true) {
            System.out.println(prompt);
            for (int i = 0; i < values.length; i++) {
                System.out.printf("  %d) %s%n", i + 1, values[i].name());
            }
            System.out.print("Enter a number (1-" + values.length + "): ");

            String input = scanner.nextLine().trim();
            try {
                int n = Integer.parseInt(input);
                if (n >= 1 && n <= values.length) {
                    return values[n - 1];
                }
            } catch (NumberFormatException ignored) {
                // fall through to error message
            }

            System.out.println("Invalid selection. Please enter a number from 1 to " + values.length + ".");
        }
    }
}
