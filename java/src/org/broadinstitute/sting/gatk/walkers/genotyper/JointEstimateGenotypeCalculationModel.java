package org.broadinstitute.sting.gatk.walkers.genotyper;

import org.broadinstitute.sting.utils.*;
import org.broadinstitute.sting.utils.genotype.*;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;

import java.util.*;

public class JointEstimateGenotypeCalculationModel extends GenotypeCalculationModel {

    protected JointEstimateGenotypeCalculationModel() {}

    // because the null allele frequencies are constant for a given N,
    // we cache the results to avoid having to recompute everything
    private HashMap<Integer, double[]> nullAlleleFrequencyCache = new HashMap<Integer, double[]>();

    // because the Hardy-Weinberg values for a given frequency are constant,
    // we cache the results to avoid having to recompute everything
    private HashMap<Double, Pair<double[], double[]>> hardyWeinbergValueCache = new HashMap<Double, Pair<double[], double[]>>();

    // the allele frequency priors
    private double[] log10AlleleFrequencyPriors;

    // the allele frequency posteriors and P(f>0) for each alternate allele
    private double[][] alleleFrequencyPosteriors = new double[BaseUtils.BASES.length][];
    private double[][] log10PofDgivenAFi = new double[BaseUtils.BASES.length][];
    private double[] PofFs = new double[BaseUtils.BASES.length];

    // the minimum and actual number of points in our allele frequency estimation
    private static final int MIN_ESTIMATION_POINTS = 100; //1000;
    private int frequencyEstimationPoints;

    // the GenotypeLikelihoods map
    private HashMap<String, GenotypeLikelihoods> GLs = new HashMap<String, GenotypeLikelihoods>();

    private enum GenotypeType { REF, HET, HOM }


    public Pair<List<GenotypeCall>, GenotypeMetaData> calculateGenotype(RefMetaDataTracker tracker, char ref, AlignmentContext context, DiploidGenotypePriors priors) {

        // keep track of the context for each sample, overall and separated by strand
        HashMap<String, AlignmentContextBySample> contexts = splitContextBySample(context);
        if ( contexts == null )
            return null;

        calculate(ref, contexts, StratifiedContext.OVERALL);


        //double lod = overall.getPofD() - overall.getPofNull();
        //logger.debug("lod=" + lod);

        // calculate strand score
        //EMOutput forward = calculate(ref, contexts, priors, StratifiedContext.FORWARD);
        //EMOutput reverse = calculate(ref, contexts, priors, StratifiedContext.REVERSE);
        //double forwardLod = (forward.getPofD() + reverse.getPofNull()) - overall.getPofNull();
        //double reverseLod = (reverse.getPofD() + forward.getPofNull()) - overall.getPofNull();
        //logger.debug("forward lod=" + forwardLod + ", reverse lod=" + reverseLod);
        //double strandScore = Math.max(forwardLod - lod, reverseLod - lod);

        //logger.debug(String.format("LOD=%f, SLOD=%f", lod, strandScore));

        // generate the calls
        //GenotypeMetaData metadata = new GenotypeMetaData(lod, strandScore, overall.getMAF());
        //return new Pair<List<GenotypeCall>, GenotypeMetaData>(genotypeCallsFromGenotypeLikelihoods(overall, ref, contexts), metadata);
        return null;
    }

    private void calculate(char ref, HashMap<String, AlignmentContextBySample> contexts, StratifiedContext contextType) {

        initializeAlleleFrequencies(contexts.size());

        initializeGenotypeLikelihoods(ref, contexts, contextType);

        calculateAlleleFrequencyPosteriors(ref);
    }

    private void initializeAlleleFrequencies(int numSamples) {

        // calculate the number of estimation points to use:
        // it's either MIN_ESTIMATION_POINTS or 2N if that's larger
        // (add 1 for allele frequency of zero)
        frequencyEstimationPoints = Math.max(MIN_ESTIMATION_POINTS, 2 * numSamples) + 1;

        // set up the allele frequency priors
        log10AlleleFrequencyPriors = getNullAlleleFrequencyPriors(frequencyEstimationPoints);

        for (int i = 0; i < frequencyEstimationPoints; i++)
            logger.debug("Null allele frequency for MAF=" + i + "/" + (frequencyEstimationPoints-1) + ": " + log10AlleleFrequencyPriors[i]);
    }

    private double[] getNullAlleleFrequencyPriors(int N) {
        double[] AFs = nullAlleleFrequencyCache.get(N);

        // if it hasn't been calculated yet, do so now
        if ( AFs == null ) {

            // calculate sum(1/i)
            double sigma_1_over_I = 0.0;
            for (int i = 1; i < N; i++)
                sigma_1_over_I += 1.0 / (double)i;

            // delta = theta / sum(1/i)
            double delta = heterozygosity / sigma_1_over_I;

            // calculate the null allele frequencies for 1-N
            AFs = new double[N];
            double sum = 0.0;
            for (int i = 1; i < N; i++) {
                double value = delta / (double)i;
                AFs[i] = Math.log10(value);
                sum += value;
            }

            // null frequency for AF=0 is (1 - sum(all other frequencies))
            AFs[0] = Math.log10(1.0 - sum);

            nullAlleleFrequencyCache.put(N, AFs);
        }

        return AFs;
    }

    private void initializeGenotypeLikelihoods(char ref, HashMap<String, AlignmentContextBySample> contexts, StratifiedContext contextType) {
        GLs.clear();

        for ( String sample : contexts.keySet() ) {
            AlignmentContextBySample context = contexts.get(sample);
            ReadBackedPileup pileup = new ReadBackedPileup(ref, context.getContext(contextType));

            // create the GenotypeLikelihoods object
            GenotypeLikelihoods GL = GenotypeLikelihoodsFactory.makeGenotypeLikelihoods(baseModel, new DiploidGenotypePriors(), defaultPlatform);
            GL.setVerbose(VERBOSE);
            GL.add(pileup, true);

            GLs.put(sample, GL);
        }
    }

    private void calculateAlleleFrequencyPosteriors(char ref) {

        // initialization
        for ( char altAllele : BaseUtils.BASES ) {
            int baseIndex = BaseUtils.simpleBaseToBaseIndex(altAllele);
            alleleFrequencyPosteriors[baseIndex] = new double[frequencyEstimationPoints];
            log10PofDgivenAFi[baseIndex] = new double[frequencyEstimationPoints];
        }
        DiploidGenotype refGenotype = DiploidGenotype.createHomGenotype(ref);
        
        // for each minor allele frequency
        for (int i = 0; i < frequencyEstimationPoints; i++) {
            double f = (double)i / (double)(frequencyEstimationPoints-1);

            DiploidGenotypePriors hardyWeinberg = getHardyWeinbergValues(f, ref);

            // for each sample
            for ( GenotypeLikelihoods GL : GLs.values() ) {

                // set the GL prior to be the AF priors and get the corresponding posteriors
                //GL.setPriors(hardyWeinberg);
                GL.setPriors(new DiploidGenotypePriors());
                double[] posteriors = GL.getPosteriors();

                // get the ref data
                double refPosterior = posteriors[refGenotype.ordinal()];
                String refStr = String.valueOf(ref);

                // for each alternate allele
                for ( char altAllele : BaseUtils.BASES ) {
                    if ( altAllele == ref )
                        continue;

                    int baseIndex = BaseUtils.simpleBaseToBaseIndex(altAllele);

                    DiploidGenotype hetGenotype = ref < altAllele ? DiploidGenotype.valueOf(refStr + String.valueOf(altAllele)) : DiploidGenotype.valueOf(String.valueOf(altAllele) + refStr);
                    DiploidGenotype homGenotype = DiploidGenotype.createHomGenotype(altAllele);

                    double[] allelePosteriors = new double[] { refPosterior, posteriors[hetGenotype.ordinal()], posteriors[homGenotype.ordinal()] };
                    normalizeFromLog10(allelePosteriors);
                    //logger.debug("Normalized posteriors for " + altAllele + ": " + allelePosteriors[0] + " " + allelePosteriors[1] + " " + allelePosteriors[2]);

                    // calculate the posterior weighted frequencies
                    double[] HWvalues = hardyWeinbergValueCache.get(f).first;
                    double PofDgivenAFi = 0.0;
                    PofDgivenAFi += HWvalues[GenotypeType.REF.ordinal()] * allelePosteriors[GenotypeType.REF.ordinal()];
                    PofDgivenAFi += HWvalues[GenotypeType.HET.ordinal()] * allelePosteriors[GenotypeType.HET.ordinal()];
                    PofDgivenAFi += HWvalues[GenotypeType.HOM.ordinal()] * allelePosteriors[GenotypeType.HOM.ordinal()];
                    log10PofDgivenAFi[baseIndex][i] += Math.log10(PofDgivenAFi);
                }
            }
        }

        // for each alternate allele
        for ( char altAllele : BaseUtils.BASES ) {
            if ( altAllele == ref )
                continue;

            int baseIndex = BaseUtils.simpleBaseToBaseIndex(altAllele);

            for (int i = 0; i < frequencyEstimationPoints; i++)
                logger.debug("P(D|AF=" + i + ") for alt allele " + altAllele + ": " + log10PofDgivenAFi[baseIndex][i]);

            // multiply by null allele frequency priors to get AF posteriors, then normalize
            for (int i = 0; i < frequencyEstimationPoints; i++)
                alleleFrequencyPosteriors[baseIndex][i] = log10AlleleFrequencyPriors[i] + log10PofDgivenAFi[baseIndex][i];
            normalizeFromLog10(alleleFrequencyPosteriors[baseIndex]);

            for (int i = 0; i < frequencyEstimationPoints; i++)
                logger.debug("Allele frequency posterior estimate for alt allele " + altAllele + " MAF=" + i + "/" + (frequencyEstimationPoints-1) + ": " + alleleFrequencyPosteriors[baseIndex][i]);

            // calculate p(f>0)
            double sum = 0.0;
            for (int i = 1; i < frequencyEstimationPoints; i++)
                sum += alleleFrequencyPosteriors[baseIndex][i];
            PofFs[baseIndex] = Math.min(sum, 1.0); // deal with precision errors
            logger.info("P(f>0), LOD for alt allele " + altAllele + ": " + Math.log10(PofFs[baseIndex]) + ", " +  (Math.log10(PofFs[baseIndex]) - Math.log10(alleleFrequencyPosteriors[baseIndex][0])));
        }
    }

    private DiploidGenotypePriors getHardyWeinbergValues(double f, char ref) {
        Pair<double[], double[]> HWvalues = hardyWeinbergValueCache.get(f);

        // if it hasn't been calculated yet, do so now
        if ( HWvalues == null ) {

            // create Hardy-Weinberg based allele frequencies (p^2, 2pq, q^2) converted to log-space
            double p = 1.0 - f;
            double q = f;

            // allele frequencies don't actually equal 0...
            if ( MathUtils.compareDoubles(q, 0.0) == 0 ) {
                q = MINIMUM_ALLELE_FREQUENCY;
                p -= MINIMUM_ALLELE_FREQUENCY;
            } else if ( MathUtils.compareDoubles(p, 0.0) == 0 ) {
                p = MINIMUM_ALLELE_FREQUENCY;
                q -= MINIMUM_ALLELE_FREQUENCY;
            }

            double[] freqs = new double[] { Math.pow(p, 2), 2.0 * p * q, Math.pow(q, 2) };
            double[] log10Freqs = new double[] { Math.log10(freqs[0]), Math.log10(freqs[1]), Math.log10(freqs[2]) };
            HWvalues = new Pair<double[], double[]>(freqs, log10Freqs);
            hardyWeinbergValueCache.put(f, HWvalues);
        }

        // create the priors based on the HW frequencies
        double[] alleleFreqPriors = new double[10];
        for ( DiploidGenotype g : DiploidGenotype.values() ) {
            if ( g.isHomRef(ref) )
                alleleFreqPriors[g.ordinal()] = HWvalues.second[GenotypeType.REF.ordinal()];
            else if ( g.isHomVar(ref) )
                alleleFreqPriors[g.ordinal()] = HWvalues.second[GenotypeType.HOM.ordinal()];
            else if ( g.isHetRef(ref) )
                alleleFreqPriors[g.ordinal()] = HWvalues.second[GenotypeType.HET.ordinal()];
            else // g is het non-ref
                alleleFreqPriors[g.ordinal()] = 0.0;
        }

        return new DiploidGenotypePriors(alleleFreqPriors);
    }

    private void normalizeFromLog10(double[] array) {
        // for precision purposes, we need to add (or really subtract, since they're
        // all negative) the largest value; also, we need to convert to normal-space.
        double maxValue = findMaxEntry(array);
        for (int i = 0; i < array.length; i++)
            array[i] = Math.pow(10, array[i] - maxValue);

        // normalize
        double sum = 0.0;
        for (int i = 0; i < array.length; i++)
            sum += array[i];
        for (int i = 0; i < array.length; i++)
            array[i] /= sum;
    }

    private static double findMaxEntry(double[] array) {
        double max = array[0];
        for (int index = 1; index < array.length; index++) {
            if ( array[index] > max )
                max = array[index];
        }
        return max;
    }
}