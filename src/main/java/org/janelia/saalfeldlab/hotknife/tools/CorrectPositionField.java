package org.janelia.saalfeldlab.hotknife.tools;

import java.io.IOException;

import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import net.imglib2.realtransform.RealTransform;

public class CorrectPositionField 
{
	public static void main( String[] args ) throws IOException
	{
		final N5Reader n5 = new N5FSReader("/nrs/flyem/render/n5/Z0720_07m_BR");

		final String groupOriginal = "/surface_align_final/pass12";
		final String groupDeformed = "/surface-align-BR/06-26/run_20211123_144300/pass12";

		final String[] datasetNamesOriginal = n5.getAttribute(groupOriginal, "datasets", String[].class);
		final String[] transformDatasetNamesOriginal = n5.getAttribute(groupOriginal, "transforms", String[].class);
		final double[] boundsMinOriginal = n5.getAttribute(groupOriginal, "boundsMin", double[].class);
		final double[] boundsMaxOriginal = n5.getAttribute(groupOriginal, "boundsMax", double[].class);

		final RealTransform[] realTransforms = new RealTransform[datasetNamesOriginal.length];
		for (int i = 0; i < datasetNamesOriginal.length; ++i) {
			System.out.println( "z=" + i + " >>> " + transformDatasetNamesOriginal[i] );
			realTransforms[i] = Transform.loadScaledTransform(
					n5,
					groupOriginal + "/" + transformDatasetNamesOriginal[i]);
		}

	}

}
