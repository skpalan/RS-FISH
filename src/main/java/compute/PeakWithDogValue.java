/*-
 * #%L
 * A plugin for radial symmetry localization on smFISH (and other) images.
 * %%
 * Copyright (C) 2016 - 2025 RS-FISH developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package compute;

import net.imglib2.Point;

/**
 * A Point that also stores the DoG (Difference-of-Gaussian) response value.
 * This allows filtering peaks at multiple thresholds without recomputing DoG.
 * 
 * @author Jialin Liu (skpalan)
 */
public class PeakWithDogValue extends Point {
	
	private final double dogValue;
	
	/**
	 * Create a peak with its DoG response value
	 * 
	 * @param point The peak location
	 * @param dogValue The DoG response value at this location
	 */
	public PeakWithDogValue(final Point point, final double dogValue) {
		super(point);
		this.dogValue = dogValue;
	}
	
	/**
	 * Create a peak with explicit coordinates and DoG value
	 * 
	 * @param position The peak coordinates
	 * @param dogValue The DoG response value at this location
	 */
	public PeakWithDogValue(final long[] position, final double dogValue) {
		super(position);
		this.dogValue = dogValue;
	}
	
	/**
	 * Get the DoG response value at this peak location
	 * 
	 * @return DoG response value
	 */
	public double getDogValue() {
		return dogValue;
	}
	
	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append("PeakWithDogValue[");
		for (int d = 0; d < numDimensions(); ++d) {
			sb.append(getLongPosition(d));
			if (d < numDimensions() - 1)
				sb.append(", ");
		}
		sb.append(", DoG=");
		sb.append(dogValue);
		sb.append("]");
		return sb.toString();
	}
}
