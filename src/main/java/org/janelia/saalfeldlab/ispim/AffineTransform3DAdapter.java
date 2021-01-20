/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.ispim;

import java.lang.reflect.Type;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import net.imglib2.realtransform.AffineTransform3D;

/**
 * Data object for iSPIM stack slices.
 *
 * @author Stephan Preibisch
 */
public class AffineTransform3DAdapter implements JsonDeserializer<AffineTransform3D>, JsonSerializer<AffineTransform3D> {

	@Override
	public AffineTransform3D deserialize(
			final JsonElement json,
			final Type typeOfT,
			final JsonDeserializationContext context) throws JsonParseException {

		final JsonArray array = json.getAsJsonArray();
		final double[] values = new double[array.size()];
		for (int i = 0; i < values.length; ++i)
			values[i] = array.get(i).getAsDouble();
		final AffineTransform3D affine = new AffineTransform3D();
		affine.set(values);
		return affine;
	}

	@Override
	public final JsonElement serialize(
			final AffineTransform3D affine,
			final Type typeOfSrc,
			final JsonSerializationContext context) {

		return context.serialize(affine.getRowPackedCopy());
	}
}
