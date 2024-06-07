package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.realtransform.RealTransform;

public class SparkViewDeformationFieldDifference
{
	/*
	--n5Path=/nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5/
	--scaleIndex=5
	--n5Group=/surface-align/run_20240517_200443/pass04
	--saveDir='/home/preibischs@hhmi.org/workspace/hot-knife/tmp'
	*/
	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5")
		private String n5Path;

		@Option(name = "--n5Group1", required = true, usage = "N5 group, e.g. /surface_align/pass02")
		private String n5Group1 = null;

		@Option(name = "--n5Group2", required = true, usage = "N5 group, e.g. /surface_align/pass10")
		private String n5Group2 = null;

		@Option(name = "--transform1", required = true, usage = "first transform flat.Sec26.top.face")
		private String transform1 = null;

		@Option(name = "--transform2", required = true, usage = "second transform flat.Sec27.bot.face")
		private String transform2 = null;

		@Option(name = "--scaleIndex", required = true, usage = "scale index for visualization, e.g. 4 (means scale = 1.0 / 2^4)")
		private int transformScaleIndex;

		@Option(name = "-o", aliases = {"--saveDir"}, required = true, usage = "directory for saving differences in transformation fields")
		private String saveDir;

		@Option(name = "--localSparkBindAddress", required = false, usage = "specify Spark bind address as localhost")
		private boolean localSparkBindAddress = false;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);

				parsedSuccessfully = true;

			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		public String getN5Path() { return n5Path; }
		public int getScaleIndex() { return transformScaleIndex; }
		public String saveDir() { return saveDir; }
		public String n5Group1() { return n5Group1; }
		public String n5Group2() { return n5Group2; }
		public String transform1() { return transform1; }
		public String transform2() { return transform2; }
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException
	{

		final SparkViewDeformationFieldDifference.Options options = new SparkViewDeformationFieldDifference.Options(args);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		final String n5Path = options.getN5Path();
		final String n5Group1 = options.n5Group1();
		final String n5Group2 = options.n5Group2();
		final int scaleIndex = options.getScaleIndex();
		final String transform1 = options.transform1();
		final String transform2 = options.transform2();

		{
			final N5Reader n5 = new N5FSReader(n5Path);

			final double[] boundsMin1 = n5.getAttribute(transform1, "boundsMin", double[].class);
			final double[] boundsMin2 = n5.getAttribute(transform2, "boundsMin", double[].class);
			final double transformScale1 = n5.getAttribute(transform1, "scale", double.class);
			final double transformScale2 = n5.getAttribute(transform2, "scale", double.class);

			final RealTransform t1 = Transform.loadScaledTransform(n5, n5Group1 + "/" + transform1, transformScale1, boundsMin1 );
			final RealTransform t2 = Transform.loadScaledTransform(n5, n5Group2 + "/" + transform2, transformScale2, boundsMin2 );

			final RealTransform sT1 = Transform.createScaledRealTransform( t1, scaleIndex );
			final RealTransform sT2 = Transform.createScaledRealTransform( t2, scaleIndex );


		}
	}
}
