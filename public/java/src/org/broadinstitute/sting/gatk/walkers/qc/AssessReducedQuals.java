package org.broadinstitute.sting.gatk.walkers.qc;

import org.broadinstitute.sting.commandline.Argument;
import org.broadinstitute.sting.commandline.Output;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.walkers.LocusWalker;
import org.broadinstitute.sting.gatk.walkers.TreeReducible;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.MathUtils;
import org.broadinstitute.sting.utils.pileup.PileupElement;
import org.broadinstitute.sting.utils.pileup.ReadBackedPileup;

import java.io.PrintStream;
import java.util.List;

/**
 * Emits intervals in which the differences between the original and reduced bam quals are bigger epsilon (unless the quals of
 * the reduced bam are above sufficient threshold)
 *
 * <h2>Input</h2>
 * <p>
 * The original and reduced BAM files.
 * </p>
 *
 * <h2>Output</h2>
 * <p>
 * A list of intervals in which the differences between the original and reduced bam quals are bigger epsilon.
 * </p>
 *
 * <h2>Examples</h2>
 * <pre>
 * java -Xmx2g -jar GenomeAnalysisTK.jar \
 *   -I:original original.bam \
 *   -I:reduced reduced.bam \
 *   -R ref.fasta \
 *   -T AssessReducedQuals \
 *   -o output.intervals
 * </pre>
 *
 * @author ami
 */

public class AssessReducedQuals extends LocusWalker<GenomeLoc, GenomeLoc> implements TreeReducible<GenomeLoc> {

    private static final String reduced = "reduced";
    private static final String original = "original";
    private static final int originalQualsIndex = 0;
    private static final int reducedQualsIndex = 1;

    @Argument(fullName = "sufficientQualSum", shortName = "sufficientQualSum", doc = "When a reduced bam qual sum is above this threshold, it passes even without comparing to the non-reduced bam ", required = false)
    public int sufficientQualSum = 600;

    @Argument(fullName = "qual_epsilon", shortName = "epsilon", doc = "when |Quals_reduced_bam - Quals_original_bam| > epsilon*Quals_original_bam we output this interval", required = false)
    public int qual_epsilon = 0;

    @Argument(fullName = "debugLevel", shortName = "debug", doc = "debug mode on")   // TODO -- best to make this optional
    public int debugLevel = 0;   // TODO -- best to make this an enum or boolean

    @Output
    protected PrintStream out;

    public void initialize() {
        if (debugLevel != 0)
            out.println("  Debug mode" +
                        "Debug:\tsufficientQualSum: "+sufficientQualSum+ "\n " +
                        "Debug:\tqual_epsilon: "+qual_epsilon);
    }

    @Override
    public boolean includeReadsWithDeletionAtLoci() { return true; }

    @Override
    public GenomeLoc map(RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context) {
        if ( tracker == null )
            return null;

        boolean reportLocus;
        final int[] quals = getPileupQuals(context.getBasePileup());
        if (debugLevel != 0)
            out.println("Debug:\tLocus Quals\t"+ref.getLocus()+"\toriginal\t"+quals[originalQualsIndex]+"\treduced\t"+quals[reducedQualsIndex]);
        final int epsilon = MathUtils.fastRound(quals[originalQualsIndex]*qual_epsilon);
        final int calcOriginalQuals = Math.min(quals[originalQualsIndex],sufficientQualSum);
        final int calcReducedQuals = Math.min(quals[reducedQualsIndex],sufficientQualSum);
        final int OriginalReducedQualDiff = calcOriginalQuals - calcReducedQuals;
        reportLocus = OriginalReducedQualDiff > epsilon || OriginalReducedQualDiff < -1*epsilon;
        if(debugLevel != 0 && reportLocus)
            out.println("Debug:\tEmited Locus\t"+ref.getLocus()+"\toriginal\t"+quals[originalQualsIndex]+"\treduced\t"+quals[reducedQualsIndex]+"\tepsilon\t"+epsilon+"\tdiff\t"+OriginalReducedQualDiff);

        return reportLocus ? ref.getLocus() : null;
    }

    private final int[] getPileupQuals(final ReadBackedPileup readPileup) {

        final int[] quals = new int[2];
        String[] printPileup = {"Debug 2:\toriginal pileup:\t"+readPileup.getLocation()+"\nDebug 2:----------------------------------\n",
                                "Debug 2:\t reduced pileup:\t"+readPileup.getLocation()+"\nDebug 2:----------------------------------\n"};

        for( PileupElement p : readPileup ){
            final List<String> tags = getToolkit().getReaderIDForRead(p.getRead()).getTags().getPositionalTags();
            if ( isGoodRead(p,tags) ){
                final int tempQual = (int)(p.getQual()) * p.getRepresentativeCount();
                final int tagIndex = getTagIndex(tags);
                quals[tagIndex] += tempQual;
                if(debugLevel == 2)
                    printPileup[tagIndex] += "\tDebug 2: ("+p+")\tMQ="+p.getMappingQual()+":QU="+p.getQual()+":RC="+p.getRepresentativeCount()+":OS="+p.getOffset()+"\n";
            }
        }
        if(debugLevel == 2){
            out.println(printPileup[originalQualsIndex]);
            out.println(printPileup[reducedQualsIndex]);
        }
        return quals;
    }

    // TODO -- arguments/variables should be final, not method declaration
    private final boolean isGoodRead(PileupElement p, List<String> tags){
        // TODO -- this isn't quite right.  You don't need the tags here.  Instead, you want to check whether the read itself (which
        // TODO --  you can get from the PileupElement) is a reduced read (not all reads from the reduced bam are reduced) and only
        // TODO --  for them do you want to ignore that min mapping quality cutoff (but you *do* still want the min base cutoff).
        return !p.isDeletion() && (tags.contains(reduced) || (tags.contains(original) && (int)p.getQual() >= 20 && p.getMappingQual() >= 20));
    }

    private final int getTagIndex(List<String> tags){
        return tags.contains(reduced) ? 1 : 0;
    }

    @Override
    public void onTraversalDone(GenomeLoc sum) {
        if ( sum != null )
            out.println(sum);
    }

    @Override
    public GenomeLoc treeReduce(GenomeLoc lhs, GenomeLoc rhs) {
        if ( lhs == null )
            return rhs;

        if ( rhs == null )
            return lhs;

        // if contiguous, just merge them
        if ( lhs.contiguousP(rhs) )
            return getToolkit().getGenomeLocParser().createGenomeLoc(lhs.getContig(), lhs.getStart(), rhs.getStop());

        // otherwise, print the lhs and start over with the rhs
        out.println(lhs);
        return rhs;
    }

    @Override
    public GenomeLoc reduceInit() {
        return null;
    }

    @Override
    public GenomeLoc reduce(GenomeLoc value, GenomeLoc sum) {
        if ( value == null )
            return sum;

        if ( sum == null )
            return value;

        // if contiguous, just merge them
        if ( sum.contiguousP(value) )
            return getToolkit().getGenomeLocParser().createGenomeLoc(sum.getContig(), sum.getStart(), value.getStop());

        // otherwise, print the sum and start over with the value
        out.println(sum);
        return value;
    }
}
