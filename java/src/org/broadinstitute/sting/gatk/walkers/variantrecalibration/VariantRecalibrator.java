/*
 * Copyright (c) 2010 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers.variantrecalibration;

import org.broad.tribble.dbsnp.DbSNPFeature;
import org.broad.tribble.util.variantcontext.VariantContext;
import org.broad.tribble.vcf.*;
import org.broadinstitute.sting.commandline.*;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.contexts.variantcontext.VariantContextUtils;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.ReferenceOrderedDataSource;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.refdata.utils.helpers.DbSNPHelper;
import org.broadinstitute.sting.gatk.walkers.RodWalker;
import org.broadinstitute.sting.utils.*;
import org.broadinstitute.sting.utils.collections.ExpandingArrayList;
import org.broadinstitute.sting.utils.collections.NestedHashMap;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.vcf.VCFUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.*;

/**
 * Applies calibrated variant cluster parameters to variant calls to produce an accurate and informative variant quality score
 *
 * @author rpoplin
 * @since Mar 17, 2010
 *
 * @help.summary Applies calibrated variant cluster parameters to variant calls to produce an accurate and informative variant quality score
 */

public class VariantRecalibrator extends RodWalker<ExpandingArrayList<VariantDatum>, ExpandingArrayList<VariantDatum>> {

    /////////////////////////////
    // Inputs
    /////////////////////////////
    @Input(fullName="cluster_file", shortName="clusterFile", doc="The input cluster file generated by GenerateVariantClusters", required=true)
    private File CLUSTER_FILE;

    /////////////////////////////
    // Outputs
    /////////////////////////////
    @Output(fullName="tranches_file", shortName="tranchesFile", doc="The output tranches file used by ApplyVariantCuts", required=true)
    private PrintStream TRANCHES_FILE;
    @Output(fullName="report_dat_file", shortName="reportDatFile", doc="The output report .dat file used with Rscript to create the optimization curve PDF file", required=true)
    private File REPORT_DAT_FILE;
    @Output(doc="File to which recalibrated variants should be written", required=true)
    private VCFWriter vcfWriter = null;

    /////////////////////////////
    // Command Line Arguments
    /////////////////////////////
    @Argument(fullName="target_titv", shortName="titv", doc="The expected novel Ti/Tv ratio to use when calculating FDR tranches and for display on optimization curve output figures. (~~2.07 for whole genome experiments)", required=true)
    private double TARGET_TITV = 2.07;
    @Argument(fullName="backOff", shortName="backOff", doc="The Gaussian back off factor, used to prevent overfitting by enlarging the Gaussians.", required=false)
    private double BACKOFF_FACTOR = 1.3;
    @Argument(fullName="desired_num_variants", shortName="dV", doc="The desired number of variants to keep in a theoretically filtered set", required=false)
    private int DESIRED_NUM_VARIANTS = 0;
    @Argument(fullName="ignore_all_input_filters", shortName="ignoreAllFilters", doc="If specified the optimizer will use variants even if the FILTER column is marked in the VCF file", required=false)
    private boolean IGNORE_ALL_INPUT_FILTERS = false;
    @Argument(fullName="ignore_filter", shortName="ignoreFilter", doc="If specified the optimizer will use variants even if the specified filter name is marked in the input VCF file", required=false)
    private String[] IGNORE_INPUT_FILTERS = null;
    @Argument(fullName="priorNovel", shortName="priorNovel", doc="A prior on the quality of novel variants, a phred scaled probability of being true.", required=false)
    private double PRIOR_NOVELS = 2.0;
    @Argument(fullName="priorDBSNP", shortName="priorDBSNP", doc="A prior on the quality of dbSNP variants, a phred scaled probability of being true.", required=false)
    private double PRIOR_DBSNP = 10.0;
    @Argument(fullName="priorHapMap", shortName="priorHapMap", doc="A prior on the quality of HapMap variants, a phred scaled probability of being true.", required=false)
    private double PRIOR_HAPMAP = 15.0;
    @Argument(fullName="prior1KG", shortName="prior1KG", doc="A prior on the quality of 1000 Genomes Project variants, a phred scaled probability of being true.", required=false)
    private double PRIOR_1KG = 12.0;
    @Argument(fullName="FDRtranche", shortName="tranche", doc="The levels of novel false discovery rate (FDR, implied by ti/tv) at which to slice the data. (in percent, that is 1.0 for 1 percent)", required=false)
    private Double[] FDR_TRANCHES = null;
    @Argument(fullName = "path_to_Rscript", shortName = "Rscript", doc = "The path to your implementation of Rscript. For Broad users this is maybe /broad/tools/apps/R-2.6.0/bin/Rscript", required=false)
    private String PATH_TO_RSCRIPT = "Rscript";
    @Argument(fullName = "path_to_resources", shortName = "resources", doc = "Path to resources folder holding the Sting R scripts.", required=false)
    private String PATH_TO_RESOURCES = "R/";
    @Argument(fullName="singleton_fp_rate", shortName="fp_rate", doc="Prior expectation that a singleton call would be a FP", required=false)
    private double SINGLETON_FP_RATE = 0.5;
    @Argument(fullName="max_ac_prior", shortName="maxACPrior", doc="Maximum value for the prior expectation based on allele count. Needed because (1 - 0.5^x) approaches 1.0 very quickly.", required=false)
    private double MAX_AC_PRIOR = 0.99;
    @Argument(fullName="dontTrustACField", shortName="dontTrustACField", doc="If specified the VR will not use the AC field and will instead always parse the genotypes to figure out how many variant chromosomes there are at a given site.", required=false)
    private boolean NEVER_TRUST_AC_FIELD = false;

    /////////////////////////////
    // Debug Arguments
    /////////////////////////////
    @Hidden
    @Argument(fullName = "NoByHapMapValidationStatus", shortName = "NoByHapMapValidationStatus", doc = "Don't consider sites in dbsnp rod tagged as by-hapmap validation status as real HapMap sites. FOR DEBUGGING PURPOSES ONLY.", required=false)
    private Boolean NO_BY_HAPMAP_VALIDATION_STATUS = false;
    @Hidden
    @Argument(fullName = "qual", shortName = "qual", doc = "Don't use sites with original quality scores below the qual threshold. FOR DEBUGGING PURPOSES ONLY.", required=false)
    private double QUAL_THRESHOLD = 0.0;
    @Hidden
    @Argument(fullName="quality_scale_factor", shortName="qScaleFactor", doc="Multiply all final quality scores by this value. FOR DEBUGGING PURPOSES ONLY.", required=false)
    private double QUALITY_SCALE_FACTOR = 1.0;


    /////////////////////////////
    // Private Member Variables
    /////////////////////////////
    private VariantOptimizationModel.Model OPTIMIZATION_MODEL = VariantOptimizationModel.Model.GAUSSIAN_MIXTURE_MODEL;
    private VariantGaussianMixtureModel theModel = null;
    private Set<String> ignoreInputFilterSet = null;
    private Set<String> inputNames = new HashSet<String>();
    private NestedHashMap priorCache = new NestedHashMap();
    private boolean trustACField = false;
    private double maxQualObserved = 0.0;

    //---------------------------------------------------------------------------------------------------------------
    //
    // initialize
    //
    //---------------------------------------------------------------------------------------------------------------

    public void initialize() {
        if( !PATH_TO_RESOURCES.endsWith("/") ) { PATH_TO_RESOURCES = PATH_TO_RESOURCES + "/"; }

        if( IGNORE_INPUT_FILTERS != null ) {
            ignoreInputFilterSet = new TreeSet<String>(Arrays.asList(IGNORE_INPUT_FILTERS));
        }

        switch (OPTIMIZATION_MODEL) {
            case GAUSSIAN_MIXTURE_MODEL:
                theModel = new VariantGaussianMixtureModel( TARGET_TITV, CLUSTER_FILE, BACKOFF_FACTOR );
                if ( SINGLETON_FP_RATE != -1 ) {
                    theModel.setSingletonFPRate(SINGLETON_FP_RATE);
                }
                break;
            //case K_NEAREST_NEIGHBORS:
            //    theModel = new VariantNearestNeighborsModel( dataManager, TARGET_TITV, NUM_KNN );
            //    break;
            default:
                throw new UserException.BadArgumentValue("OPTIMIZATION_MODEL", "Variant Optimization Model is unrecognized. Implemented options are GAUSSIAN_MIXTURE_MODEL and K_NEAREST_NEIGHBORS" );
        }

        boolean foundDBSNP = false;
        for( ReferenceOrderedDataSource d : this.getToolkit().getRodDataSources() ) {
            if( d.getName().startsWith("input") ) {
                inputNames.add(d.getName());
                logger.info("Found input variant track with name " + d.getName());
            } else if ( d.getName().equals(DbSNPHelper.STANDARD_DBSNP_TRACK_NAME) ) {
                logger.info("Found dbSNP track with prior probability = Q" + PRIOR_DBSNP);
                if( !NO_BY_HAPMAP_VALIDATION_STATUS ) {
                    logger.info("\tsites in dbSNP track tagged with by-hapmap validation status will be given prior probability = Q" + PRIOR_HAPMAP);
                }
                foundDBSNP = true;
            } else if ( d.getName().equals("hapmap") ) {
                logger.info("Found HapMap track with prior probability = Q" + PRIOR_HAPMAP);
            } else if ( d.getName().equals("1kg") ) {
                logger.info("Found 1KG track for with prior probability = Q" + PRIOR_1KG);
            } else {
                logger.info("Not evaluating ROD binding " + d.getName());
            }
        }

        if(!foundDBSNP) {
            throw new UserException.CommandLineException("dbSNP track is required. This calculation is critically dependent on being able to distinguish known and novel sites.");
        }

        // setup the header fields
        final Set<VCFHeaderLine> hInfo = new HashSet<VCFHeaderLine>();
        final TreeSet<String> samples = new TreeSet<String>();
        hInfo.addAll(VCFUtils.getHeaderFields(getToolkit(), inputNames));
        hInfo.add(new VCFInfoHeaderLine("OQ", 1, VCFHeaderLineType.Float, "The original variant quality score"));
        hInfo.add(new VCFInfoHeaderLine("LOD", 1, VCFHeaderLineType.Float, "The log odds ratio calculated by the VR algorithm which was turned into the phred scaled recalibrated quality score"));
        samples.addAll(SampleUtils.getUniqueSamplesFromRods(getToolkit(), inputNames));

        final VCFHeader vcfHeader = new VCFHeader(hInfo, samples);
        vcfWriter.writeHeader(vcfHeader);

        // Set up default values for the FDR tranches if necessary
        if( FDR_TRANCHES == null ) {
            FDR_TRANCHES = new Double[4];
            FDR_TRANCHES[0] = 0.1;
            FDR_TRANCHES[1] = 1.0;
            FDR_TRANCHES[2] = 5.0;
            FDR_TRANCHES[3] = 10.0;
        }
    }

    //---------------------------------------------------------------------------------------------------------------
    //
    // map
    //
    //---------------------------------------------------------------------------------------------------------------

    public ExpandingArrayList<VariantDatum> map( RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context ) {
        final ExpandingArrayList<VariantDatum> mapList = new ExpandingArrayList<VariantDatum>();

        if( tracker == null ) { // For some reason RodWalkers get map calls with null trackers
            return mapList;
        }

        for( final VariantContext vc : tracker.getVariantContexts(ref, inputNames, null, context.getLocation(), false, false) ) {
            if( vc != null && vc.isSNP() ) {
                if( !vc.isFiltered() || IGNORE_ALL_INPUT_FILTERS || (ignoreInputFilterSet != null && ignoreInputFilterSet.containsAll(vc.getFilters())) ) {
                    if( vc.getPhredScaledQual() >= QUAL_THRESHOLD ) {
                        final VariantDatum variantDatum = new VariantDatum();
                        variantDatum.isTransition = VariantContextUtils.getSNPSubstitutionType(vc).compareTo(BaseUtils.BaseSubstitutionType.TRANSITION) == 0;

                        final DbSNPFeature dbsnp = DbSNPHelper.getFirstRealSNP(tracker.getReferenceMetaData(DbSNPHelper.STANDARD_DBSNP_TRACK_NAME));
                        final VariantContext vcHapMap = tracker.getVariantContext(ref, "hapmap", null, context.getLocation(), false);
                        final VariantContext vc1KG = tracker.getVariantContext(ref, "1kg", null, context.getLocation(), false);

                        variantDatum.isKnown = ( dbsnp != null );
                        double knownPrior_qScore = PRIOR_NOVELS;
                        if( vcHapMap != null || ( !NO_BY_HAPMAP_VALIDATION_STATUS && dbsnp != null && DbSNPHelper.isHapmap(dbsnp) ) ) {
                            knownPrior_qScore = PRIOR_HAPMAP;
                        } else if( vc1KG != null ) {
                            knownPrior_qScore = PRIOR_1KG;
                        } else if( dbsnp != null ) {
                            knownPrior_qScore = PRIOR_DBSNP;
                        }


                        // If we can trust the AC field then use it instead of parsing all the genotypes. Results in a substantial speed up.
                        final int alleleCount = (trustACField ? Integer.parseInt(vc.getAttribute("AC",-1).toString()) : vc.getChromosomeCount(vc.getAlternateAllele(0)));
                        if( !trustACField && !NEVER_TRUST_AC_FIELD ) {
                            if( !vc.getAttributes().containsKey("AC") ) {
                                NEVER_TRUST_AC_FIELD = true;
                            } else {
                                if( alleleCount == Integer.parseInt( vc.getAttribute("AC").toString()) ) {
                                    // The AC field is correct at this record so we trust it forever
                                    trustACField = true;
                                } else { // We found a record in which the AC field wasn't correct but we are trying to trust it
                                    throw new UserException.BadInput("AC info field doesn't match the variant chromosome count so we can't trust it! Please run with --dontTrustACField which will force the walker to parse the genotypes for each record, drastically increasing the runtime." +
                                                "First observed at " + vc.getChr() + ":" + vc.getStart());
                                }
                            }
                        }
                        if( trustACField && alleleCount == -1 ) {
                            throw new UserException.BadInput("AC info field doesn't exist for all records (although it does for some) so we can't trust it! Please run with --dontTrustACField which will force the walker to parse the genotypes for each record, drastically increasing the runtime." +
                                    "First observed at " + vc.getChr() + ":" + vc.getStart());
                        }

                        final Object[] priorKey = new Object[2];
                        priorKey[0] = alleleCount;
                        priorKey[1] = knownPrior_qScore;

                        Double priorLodFactor = (Double)priorCache.get( priorKey );

                        // If this prior factor hasn't been calculated before, do so now
                        if(priorLodFactor == null) {
                            final double knownPrior = QualityUtils.qualToProb(knownPrior_qScore);
                            final double acPrior = theModel.getAlleleCountPrior( alleleCount, MAX_AC_PRIOR );
                            final double totalPrior = 1.0 - ((1.0 - acPrior) * (1.0 - knownPrior));

                            if( MathUtils.compareDoubles(totalPrior, 1.0, 1E-8) == 0 || MathUtils.compareDoubles(totalPrior, 0.0, 1E-8) == 0 ) {
                                throw new UserException.CommandLineException("Something is wrong with the priors that were entered by the user:  Prior = " + totalPrior); // TODO - fix this up later
                            }

                            priorLodFactor = Math.log10(totalPrior) - Math.log10(1.0 - totalPrior) - Math.log10(1.0);

                            priorCache.put( priorLodFactor, false, priorKey );
                        }

                        final double pVar = theModel.evaluateVariant( vc );
                        final double lod = priorLodFactor + Math.log10(pVar);
                        variantDatum.qual = Math.abs( QUALITY_SCALE_FACTOR * QualityUtils.lodToPhredScaleErrorRate(lod) );
                        if( variantDatum.qual > maxQualObserved ) {
                            maxQualObserved = variantDatum.qual;
                        }

                        mapList.add( variantDatum );
                        final Map<String, Object> attrs = new HashMap<String, Object>(vc.getAttributes());
                        attrs.put("OQ", String.format("%.2f", vc.getPhredScaledQual()));
                        attrs.put("LOD", String.format("%.4f", lod));
                        VariantContext newVC = VariantContext.modifyPErrorFiltersAndAttributes(vc, variantDatum.qual / 10.0, new HashSet<String>(), attrs);

                        vcfWriter.add( newVC, ref.getBase() );
                    }

                } else { // not a SNP or is filtered so just dump it out to the VCF file
                    vcfWriter.add( vc, ref.getBase() ); 
                }
            }

        }
        
        return mapList;
    }

    //---------------------------------------------------------------------------------------------------------------
    //
    // reduce
    //
    //---------------------------------------------------------------------------------------------------------------

     public ExpandingArrayList<VariantDatum> reduceInit() {
        return new ExpandingArrayList<VariantDatum>();
    }

    public ExpandingArrayList<VariantDatum> reduce( final ExpandingArrayList<VariantDatum> mapValue, final ExpandingArrayList<VariantDatum> reduceSum ) {
        reduceSum.addAll( mapValue );
        return reduceSum;
    }

    public void onTraversalDone( ExpandingArrayList<VariantDatum> reduceSum ) {

        final VariantDataManager dataManager = new VariantDataManager( reduceSum, theModel.dataManager.annotationKeys );
        reduceSum.clear(); // Don't need this ever again, clean up some memory

        try {
            PrintStream reportDatFilePrintStream = new PrintStream(REPORT_DAT_FILE);
            theModel.outputOptimizationCurve( dataManager.data, reportDatFilePrintStream, TRANCHES_FILE, DESIRED_NUM_VARIANTS, FDR_TRANCHES, maxQualObserved );
        } catch ( FileNotFoundException e ) {
            throw new UserException.CouldNotCreateOutputFile(REPORT_DAT_FILE, e);
        }

        // Execute Rscript command to plot the optimization curve
        // Print out the command line to make it clear to the user what is being executed and how one might modify it
        final String rScriptOptimizationCurveCommandLine = PATH_TO_RSCRIPT + " " + PATH_TO_RESOURCES + "plot_OptimizationCurve.R" + " " + REPORT_DAT_FILE.getName() + " " + TARGET_TITV;
        final String rScriptTranchesCommandLine = PATH_TO_RSCRIPT + " " + PATH_TO_RESOURCES + "plot_Tranches.R" + " " + REPORT_DAT_FILE.getName() + " " + TARGET_TITV;
        logger.info( rScriptOptimizationCurveCommandLine );
        logger.info( rScriptTranchesCommandLine );

        // Execute the RScript command to plot the table of truth values
        try {
            Process p;
            p = Runtime.getRuntime().exec( rScriptOptimizationCurveCommandLine );
            p.waitFor();
            p = Runtime.getRuntime().exec( rScriptTranchesCommandLine );
            p.waitFor();
        } catch ( Exception e ) {
            Utils.warnUser("Unable to execute the RScript command.  While not critical to the calculations themselves, the script outputs a report that is extremely useful for confirming that the recalibration proceded as expected.  We highly recommend trying to rerun the script manually if possible.");
        }
    }
}

