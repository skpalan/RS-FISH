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
package cmd;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader;

import gui.Radial_Symmetry;
import gui.interactive.HelperFunctions;
import ij.ImageJ;
import ij.ImagePlus;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import parameters.RadialSymParams;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class RadialSymmetry implements Callable<Void> {

	// input file
	@Option(names = {"-i", "--image"}, required = true, description = "input image, N5 container, or HDF5 file path, e.g. -i /home/smFish.tif, /home/smFish.n5, or /home/smFish.h5")
	private String image = null;

	@Option(names = {"-d", "--dataset"}, required = false, description = "dataset path within N5 or HDF5 container, e.g. -d 'embryo_5_ch0/c0/s0' for N5 or -d '/data' for HDF5 (default for HDF5: '/data')")
	private String dataset = null;

	// output file
	@Option(names = {"-o", "--output"}, required = true, description = "output CSV file, e.g. -o 'embryo_5_ch0.csv'")
	private String output = null;

	// interactive mode
	@Option(names = {"--interactive"}, required = false, description = "run the plugin interactively, ImageJ window and image pop up (default: false)")
	private boolean interactive = false;

	// intensity settings
	@Option(names = {"-i0", "--minIntensity"}, required = false, description = "minimal intensity of the image (default: compute from image)")
	private double minIntensity = Double.NaN;

	@Option(names = {"-i1", "--maxIntensity"}, required = false, description = "maximal intensity of the image (default: compute from image)")
	private double maxIntensity = Double.NaN;

	// RS settings
	@Option(names = {"-a", "--anisotropy"}, required = false, description = "the anisotropy factor (scaling of z relative to xy, can be determined using the anisotropy plugin), e.g. -a 0.8 (default: 1.0)")
	private double anisotropy = 1.0;

	@Option(names = {"-r", "--ransac"}, required = false, description = "which RANSAC type to use, 0 == No RANSAC, 1 == RANSAC, 2 == Multiconsensus RANSAC (default: 1 - RANSAC)")
	private int ransac = 1;

	@Option(names = {"-s", "--sigma"}, required = false, description = "sigma for Difference-of-Gaussian (DoG) (default: 1.5)")
	private double sigma = 1.5;

	@Option(names = {"-t", "--threshold"}, required = false, description = "threshold for Difference-of-Gaussian (DoG) (default: 0.007)")
	private double threshold = 0.007;

	@Option(names = {"--multi_threshold"}, required = false, 
			description = "Multiple thresholds for DoG (comma-separated), e.g., 0.005,0.007,0.01. " +
						 "Mutually exclusive with --threshold. DoG is computed once and reused for all thresholds. " +
						 "When specified, generates separate output CSV files for each threshold.")
	private String multiThreshold = null;

	@Option(names = {"--threads"}, required = false, 
			description = "Number of threads for parallel processing. When not specified, runs single-threaded. " +
						 "Used for: (1) DoG computation, (2) parallel processing of multiple thresholds.")
	private Integer threads = null;

	@Option(names = {"--random-seed"}, required = false, 
			description = "Random seed for RANSAC reproducibility (default: random). " +
						 "Use this to ensure identical results across multiple runs.")
	private Long randomSeed = null;

	@Option(names = {"-sr", "--supportRadius"}, required = false, description = "support region radius for RANSAC (default: 3)")
	private int supportRadius = 3;

	@Option(names = {"-ir", "--inlierRatio"}, required = false, description = "Minimal ratio of gradients that agree on a spot (inliers) for RANSAC (default: 0.1)")
	private double inlierRatio = 0.1;

	@Option(names = {"-e", "--maxError"}, required = false, description = "Maximum error for intersecting gradients of a spot for RANSAC (default: 1.5)")
	private double maxError = 1.5;

	@Option(names = {"-it", "--intensityThreshold"}, required = false, description = "intensity threshold for localized spots (default: 0.0)")
	private double intensityThreshold = 0.0;

	// background method
	@Option(names = {"-bg", "--background"}, required = false, description = "Background subtraction method, 0 == None, 1 == Mean, 2==Median, 3==RANSAC on Mean, 4==RANSAC on Median (default: 0 - None)")
	private int background = 0;

	@Option(names = {"-bge", "--backgroundMaxError"}, required = false, description = "RANSAC-based background subtraction max error (default: 0.05)")
	private double backgroundMaxError = 0.05;

	@Option(names = {"-bgir", "--backgroundMinInlierRatio"}, required = false, description = "RANSAC-based background subtraction min inlier ratio (default: 0.75)")
	private double backgroundMinInlierRatio = 0.75;

	// only for multiconsensus RANSAC
	@Option(names = {"-rm", "--ransacMinNumInliers"}, required = false, description = "minimal number of inliers for Multiconsensus RANSAC (default: 20)")
	private int ransacMinNumInliers = 20;

	@Option(names = {"-rn1", "--ransacNTimesStDev1"}, required = false, description = "n: initial #inlier threshold for new spot [avg - n*stdev] for Multiconsensus RANSAC (default: 8.0)")
	private double ransacNTimesStDev1 = 8.0;

	@Option(names = {"-rn2", "--ransacNTimesStDev2"}, required = false, description = "n: final #inlier threshold for new spot [avg - n*stdev] for Multiconsensus RANSAC (default: 6.0)")
	private double ransacNTimesStDev2 = 6.0;

	@Override
	public Void call() throws Exception {

		final RadialSymParams params = new RadialSymParams();

		// Parse threads parameter
		final int numThreads;
		if (threads != null) {
			numThreads = threads;
			if (numThreads < 1) {
				throw new IllegalArgumentException("--threads must be >= 1");
			}
		} else {
			numThreads = 1; // Single-threaded by default
		}

		// Parse multi-threshold parameter
		List<Double> thresholds = null;
		if (multiThreshold != null && !multiThreshold.isEmpty()) {
			// Note: We cannot easily detect if -t/--threshold was explicitly set by the user
			// due to picocli limitations with default values. Users should avoid using both parameters.
			System.out.println("Note: Using --multi_threshold mode. If you also specified --threshold, it will be ignored.");

			// Parse comma-separated thresholds
			try {
				thresholds = Arrays.stream(multiThreshold.split(","))
					.map(String::trim)
					.map(Double::parseDouble)
					.sorted()
					.collect(Collectors.toList());

				if (thresholds.isEmpty()) {
					throw new IllegalArgumentException(
						"--multi_threshold cannot be empty");
				}

				for (double t : thresholds) {
					if (t <= 0) {
						throw new IllegalArgumentException(
							"All thresholds must be positive");
					}
				}

				System.out.println("Multi-threshold mode: " + thresholds);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(
					"Invalid --multi_threshold format. Use comma-separated numbers, e.g., 0.005,0.007,0.01");
			}
		}

		// Set random seed if specified
		if (randomSeed != null) {
			System.out.println("Using random seed: " + randomSeed);
			// TODO: Pass to RANSAC implementation
		}

		// general
		RadialSymParams.defaultAnisotropy = params.anisotropyCoefficient = anisotropy;
		RadialSymParams.defaultUseAnisotropyForDoG = params.useAnisotropyForDoG = true;
		RadialSymParams.defaultRANSACChoice = ransac;
		params.ransacSelection = ransac; //"No RANSAC", "RANSAC", "Multiconsensus RANSAC"

		params.min = minIntensity;
		params.max = maxIntensity;

		if (Double.isNaN( params.min ) )
		{
			RadialSymParams.defaultMin = minIntensity;
			params.autoMinMax = true;
		}

		if (Double.isNaN( params.max ) )
		{
			RadialSymParams.defaultMax = maxIntensity;
			params.autoMinMax = true;
		}

		// multiconsensus
		if ( ransac == 2 )
		{
			RadialSymParams.defaultMinNumInliers = params.minNumInliers = ransacMinNumInliers;
			RadialSymParams.defaultNTimesStDev1 = params.nTimesStDev1 = ransacNTimesStDev1;
			RadialSymParams.defaultNTimesStDev2 = params.nTimesStDev2 = ransacNTimesStDev2;
		}

		// advanced
		RadialSymParams.defaultSigma = params.sigma = (float)sigma;
		RadialSymParams.defaultThreshold = params.threshold = (float)threshold;
		RadialSymParams.defaultSupportRadius = params.supportRadius = supportRadius;
		RadialSymParams.defaultInlierRatio = params.inlierRatio = (float)inlierRatio;
		RadialSymParams.defaultMaxError = params.maxError = (float)maxError;
		RadialSymParams.defaultIntensityThreshold = params.intensityThreshold = intensityThreshold;
		RadialSymParams.defaultBsMethodChoice = params.bsMethod = background;
		RadialSymParams.defaultBsMaxError = params.bsMaxError = (float)backgroundMaxError;
		RadialSymParams.defaultBsInlierRatio = params.bsInlierRatio = (float)backgroundMinInlierRatio;
		RadialSymParams.defaultResultsFilePath = params.resultsFilePath = output;

		// Set numThreads in params
		params.numThreads = numThreads;

		if ( interactive )
		{
			if (thresholds != null) {
				System.err.println("WARNING: --multi_threshold is not supported in interactive mode. Ignoring.");
			}

			final ImagePlus imp = open( image, dataset );

			if ( imp == null )
			{
				System.out.println( "Could not open file: " + image  + " (if N5, dataset=" + dataset + ")");
				return null;
			}

			new ImageJ();

			if ( imp.getStackSize() > 1 )
				imp.setSlice( imp.getStackSize() / 2 );
	
			imp.resetDisplayRange();
			imp.show();
	
			new Radial_Symmetry().run( null );
		}
		else
		{
			final RandomAccessibleInterval img;

			if ( isHDF5( image ) )
			{
				// Default dataset for HDF5 files is "/data"
				final String h5dataset = ( dataset == null || dataset.length() < 1 ) ? "/data" : dataset;
				
				try
				{
					final N5HDF5Reader reader = new N5HDF5Reader( image );
					
					if ( !reader.datasetExists( h5dataset ) )
					{
						System.err.println( "ERROR: Dataset '" + h5dataset + "' not found in HDF5 file: " + image );
						System.err.println( "Available datasets:" );
						listHDF5Datasets( reader, "/", "  " );
						reader.close();
						return null;
					}
					
					img = N5Utils.open( reader, h5dataset );
					System.out.println( "Opened HDF5 file: " + image + ", dataset: " + h5dataset );
				}
				catch ( Exception e )
				{
					System.err.println( "ERROR: Failed to open HDF5 file: " + image );
					System.err.println( "  " + e.getMessage() );
					return null;
				}
				
				// Enable x-y coordinate swapping for HDF5 files to match Fiji macro convention
				params.swapXY = true;
			}
			else if ( isN5( image ) )
			{
				img = N5Utils.open( new N5FSReader( image ), dataset );
			}
			else
			{
				img = ImagePlusImgs.from( new ImagePlus( image ) );
			}

			HelperFunctions.headless = true;

		if (thresholds != null) {
			// Multi-threshold mode
			System.out.println("=== RS-FISH Multi-Threshold Mode ===");
			System.out.println("Input: " + image);
			System.out.println("Thresholds: " + thresholds);
			System.out.println("Threads: " + numThreads);
			System.out.println("Sigma: " + sigma);

			// Compute min/max if needed (same as single-threshold mode)
			if (params.autoMinMax) {
				double[] minmax = HelperFunctions.computeMinMax(img);
				if (Double.isNaN(params.min))
					params.min = (float) minmax[0];
				if (Double.isNaN(params.max))
					params.max = (float) minmax[1];
			}

			HelperFunctions.log("img min intensity=" + params.min + ", max intensity=" + params.max);

			// Un-normalized image for intensity measurement
			final RandomAccessible<FloatType> unnormalizedImg = Converters.convert(
				Views.extendMirrorSingle(img),
				(a, b) -> ((FloatType)b).setReal(((net.imglib2.type.numeric.RealType)a).getRealFloat()),
				new FloatType());

			// Normalized image for detection
			final double range = params.max - params.min;
			final double min = params.min;
			final RandomAccessible<FloatType> normalizedImg = Converters.convert(
				Views.extendMirrorSingle(img),
				(a, b) -> ((FloatType)b).setReal((((net.imglib2.type.numeric.RealType)a).getRealFloat() - min) / range),
				new FloatType());

			compute.RadialSymmetry.computeMultiThreshold(
				unnormalizedImg,
				normalizedImg,
				new FinalInterval(img),
				new FinalInterval(img),
				params,
				thresholds,
				output,
				numThreads);

			System.out.println("=== Complete ===");
			} else {
				// Original single-threshold mode
				Radial_Symmetry.runRSFISH(
					(RandomAccessible)(Object)Views.extendMirrorSingle( img ),
					new FinalInterval( img ),
					new FinalInterval( img ),
					params );
			}

			System.out.println( "done.");
		}

		return null;
	}

	protected static boolean isN5( final String image )
	{
		return new File( image ).isDirectory();// e.trim().toLowerCase().endsWith( ".n5" );
	}

	protected static boolean isHDF5( final String image )
	{
		final String lower = image.toLowerCase();
		return lower.endsWith( ".h5" ) || lower.endsWith( ".hdf5" );
	}

	protected static void listHDF5Datasets( final N5HDF5Reader reader, final String path, final String indent )
	{
		try
		{
			String[] children = reader.list( path );
			if ( children != null )
			{
				for ( String child : children )
				{
					String childPath = path.equals( "/" ) ? "/" + child : path + "/" + child;
					if ( reader.datasetExists( childPath ) )
					{
						long[] dims = reader.getDatasetAttributes( childPath ).getDimensions();
						System.err.println( indent + childPath + " [" + java.util.Arrays.toString( dims ) + "]" );
					}
					else
					{
						System.err.println( indent + childPath + "/" );
						listHDF5Datasets( reader, childPath, indent + "  " );
					}
				}
			}
		}
		catch ( Exception e )
		{
			System.err.println( indent + "Error listing " + path + ": " + e.getMessage() );
		}
	}

	protected static ImagePlus open( String image, String dataset ) throws IOException
	{
		final ImagePlus imp;

		if ( isHDF5( image ) )
		{
			// Default dataset for HDF5 files is "/data"
			final String h5dataset = ( dataset == null || dataset.length() < 1 ) ? "/data" : dataset;
			
			final N5HDF5Reader reader = new N5HDF5Reader( image );
			
			if ( !reader.datasetExists( h5dataset ) )
			{
				System.err.println( "ERROR: Dataset '" + h5dataset + "' not found in HDF5 file: " + image );
				System.err.println( "Available datasets:" );
				listHDF5Datasets( reader, "/", "  " );
				reader.close();
				throw new RuntimeException( "Dataset not found in HDF5 file" );
			}
			
			RandomAccessibleInterval img = N5Utils.open( reader, h5dataset );
			
			imp = ImageJFunctions.wrap( img, h5dataset );
			imp.setDimensions( 1, imp.getStackSize(), 1 );
			System.out.println( "Opened HDF5 file: " + image + ", dataset: " + h5dataset );
		}
		else if ( isN5( image ) )
		{
			if ( dataset == null || dataset.length() < 1 )
				throw new RuntimeException( "no dataset for the N5 container defined, please use -d 'dataset'." );

			final N5Reader n5 = new N5FSReader( image );
			RandomAccessibleInterval img = N5Utils.open( n5, dataset );

			imp = ImageJFunctions.wrap( img, dataset );
			imp.setDimensions( 1, imp.getStackSize(), 1);
		}
		else
		{
			imp = new ImagePlus( image );
		}

		return imp;
	}

	public static final void main(final String... args) {
		new CommandLine( new RadialSymmetry() ).execute( args );
	}
}
