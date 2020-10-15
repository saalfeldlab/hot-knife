/**
 *
 */
package org.janelia.saalfeldlab.hotknife.tools;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 *
 */
@Command(
		name = "hotknife",
		subcommands = {PaintHeightField.class})
public class HotKnife {

	public static void main(final String... args) {

        new CommandLine(new HotKnife()).execute(args);
    }
}
