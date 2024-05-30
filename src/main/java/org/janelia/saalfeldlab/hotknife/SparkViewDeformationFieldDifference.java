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

		@Option(name = "--n5Group", required = false, usage = "N5 group, e.g. /surface-align/run_20240517_200443/pass04")
		private String group = "/";

		@Option(name = "--scaleIndex", required = true, usage = "scale index for visualization, e.g. 4 (means scale = 1.0 / 2^4)")
		private int transformScaleIndex;

		@Option(name = "-a", required = false, usage = "list of transformation fields (sorted as pairs with 'b'), e.g. -a flat_mask.s281_m206.bot4_clahe.face OR -a pass04/flat_mask.s281_m206.bot4_clahe.face")
		private ArrayList<String> a = new ArrayList<>();

		@Option(name = "-b", required = false, usage = "list of transformation fields (sorted as pairs with 'a'), e.g. -a flat_mask.s281_m206.top4_clahe.face")
		private ArrayList<String> b = new ArrayList<>();

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
		public String getGroup() { return group; }
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException
	{

		final SparkViewDeformationFieldDifference.Options options = new SparkViewDeformationFieldDifference.Options(args);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		if ( options.a.size() != options.b.size() )
			throw new RuntimeException( "size of a and b not equal." );

		if ( options.a.size() == 0 )
			throw new RuntimeException( "a and b not specified." );

		final String n5Path = options.getN5Path();
		final String n5Group = options.getGroup();
		final int scaleIndex = options.getScaleIndex();
		final ArrayList< String > transformANames = options.a;
		final ArrayList< String > transformBNames = options.b;

		for ( int i = 0; i < options.a.size(); ++ i )
		{
			final N5Reader n5 = new N5FSReader(n5Path);

			final String transformA = transformANames.get( i );
			final String transformB = transformBNames.get( i );

			final double[] boundsMin = n5.getAttribute(transformA, "boundsMin", double[].class);
			final double transformScale = n5.getAttribute(transformA, "scale", double.class);

			final RealTransform tA = Transform.loadScaledTransform(n5, n5Group + "/" + transformA );
			final RealTransform tB = Transform.loadScaledTransform(n5, n5Group + "/" + transformB );

			final RealTransform sTA = Transform.createScaledRealTransform( tA, scaleIndex );
			final RealTransform sTB = Transform.createScaledRealTransform( tB, scaleIndex );


		}
	}
}
