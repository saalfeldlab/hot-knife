package org.janelia.saalfeldlab.hotknife.util;

import java.util.Arrays;
import java.util.List;

import org.janelia.saalfeldlab.hotknife.util.ThicknessCorrectionData.LayerInterpolator;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link ThicknessCorrectionData} class.
 *
 * @author Eric Trautman
 */
public class ThicknessCorrectionDataTest {

    @Test
    public void testGetInterpolator() {
        final List<String> correctionDataLines = Arrays.asList(
                "1 0.99",
                "2 7.51",
                "3 8.17",
                "4 8.80",
                "5 9.51",
                "6 10.21",
                "7 10.94",
                "8 11.62",
                "9 12.34",
                "10 13.05"
        );

        final ThicknessCorrectionData correctionData = new ThicknessCorrectionData(correctionDataLines);

        validateInterpolator(1, correctionData,
                             1, 0.99,
                             2, 7.51);

        validateInterpolator(2, correctionData,
                             1, 0.99,
                             2, 7.51);

        validateInterpolator(8, correctionData,
                             2, 7.51,
                             3, 8.17);

        validateInterpolator(9, correctionData,
                             4, 8.80,
                             5, 9.51);

        validateInterpolator(13, correctionData,
                             9, 12.34,
                             10, 13.05);

        validateIdentityInterpolator(0, correctionData);
        validateIdentityInterpolator(14, correctionData);
    }

    private void validateInterpolator(final long renderZ,
                                      final ThicknessCorrectionData correctionData,
                                      final long expectedPriorStackZ,
                                      final double priorCorrectedZ,
                                      final long expectedNextStackZ,
                                      final double nextCorrectedZ) {

        LayerInterpolator interpolator = correctionData.getInterpolator(renderZ);
        Assert.assertEquals("bad prior stack z for render z " + renderZ,
                            expectedPriorStackZ, interpolator.getPriorStackZ());
        Assert.assertEquals("bad next stack z for render z " + renderZ,
                            expectedNextStackZ, interpolator.getNextStackZ());

        double expectedPriorWeight = 1.0 - ((renderZ - priorCorrectedZ) / (nextCorrectedZ - priorCorrectedZ));
        Assert.assertEquals("bad prior weight for render z " + renderZ,
                            expectedPriorWeight, interpolator.getPriorWeight(), 0.01);
    }

    private void validateIdentityInterpolator(final long renderZ,
                                              final ThicknessCorrectionData correctionData) {
        validateInterpolator(renderZ, correctionData,
                             renderZ, renderZ,
                             renderZ, (renderZ + 1));
    }
}
