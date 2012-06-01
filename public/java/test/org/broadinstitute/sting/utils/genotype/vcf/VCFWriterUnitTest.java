package org.broadinstitute.sting.utils.genotype.vcf;

import net.sf.picard.reference.IndexedFastaSequenceFile;
import org.broad.tribble.AbstractFeatureReader;
import org.broad.tribble.FeatureReader;
import org.broad.tribble.Tribble;
import org.broadinstitute.sting.BaseTest;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.GenomeLocParser;
import org.broadinstitute.sting.utils.codecs.vcf.VCFCodec;
import org.broadinstitute.sting.utils.codecs.vcf.VCFHeader;
import org.broadinstitute.sting.utils.codecs.vcf.VCFHeaderLine;
import org.broadinstitute.sting.utils.codecs.vcf.VCFHeaderVersion;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.fasta.CachingIndexedFastaSequenceFile;
import org.broadinstitute.sting.utils.variantcontext.*;
import org.broadinstitute.sting.utils.variantcontext.writer.VariantContextWriter;
import org.broadinstitute.sting.utils.variantcontext.writer.VariantContextWriterFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;


/**
 * @author aaron
 *         <p/>
 *         Class VCFWriterUnitTest
 *         <p/>
 *         This class tests out the ability of the VCF writer to correctly write VCF files
 */
public class VCFWriterUnitTest extends BaseTest {
    private Set<VCFHeaderLine> metaData = new HashSet<VCFHeaderLine>();
    private Set<String> additionalColumns = new HashSet<String>();
    private File fakeVCFFile = new File("FAKEVCFFILEFORTESTING.vcf");
    private GenomeLocParser genomeLocParser;
    private IndexedFastaSequenceFile seq;

    @BeforeClass
    public void beforeTests() {
        File referenceFile = new File(hg18Reference);
        try {
            seq = new CachingIndexedFastaSequenceFile(referenceFile);
            genomeLocParser = new GenomeLocParser(seq);
        }
        catch(FileNotFoundException ex) {
            throw new UserException.CouldNotReadInputFile(referenceFile,ex);
        }
    }

    /** test, using the writer and reader, that we can output and input a VCF file without problems */
    @Test
    public void testBasicWriteAndRead() {
        VCFHeader header = createFakeHeader(metaData,additionalColumns);
        VariantContextWriter writer = VariantContextWriterFactory.create(fakeVCFFile, seq.getSequenceDictionary());
        writer.writeHeader(header);
        writer.add(createVC(header));
        writer.add(createVC(header));
        writer.close();
        VCFCodec codec = new VCFCodec();
        VCFHeader headerFromFile = null;
        FeatureReader<VariantContext> reader = AbstractFeatureReader.getFeatureReader(fakeVCFFile.getAbsolutePath(), codec, false);
        headerFromFile = (VCFHeader)reader.getHeader();

        int counter = 0;

        // validate what we're reading in
        validateHeader(headerFromFile);
        
        try {
            Iterator<VariantContext> it = reader.iterator();
            while(it.hasNext()) {
                VariantContext vc = it.next();
                counter++;
            }
            Assert.assertEquals(counter, 2);
            Tribble.indexFile(fakeVCFFile).delete();
            fakeVCFFile.delete();
        }
        catch (IOException e ) {
            throw new ReviewedStingException(e.getMessage());
        }

    }

    /**
     * create a fake header of known quantity
     * @param metaData           the header lines
     * @param additionalColumns  the additional column names
     * @return a fake VCF header
     */
    public static VCFHeader createFakeHeader(Set<VCFHeaderLine> metaData, Set<String> additionalColumns) {
        metaData.add(new VCFHeaderLine(VCFHeaderVersion.VCF4_0.getFormatString(), VCFHeaderVersion.VCF4_0.getVersionString()));
        metaData.add(new VCFHeaderLine("two", "2"));
        additionalColumns.add("extra1");
        additionalColumns.add("extra2");
        return new VCFHeader(metaData, additionalColumns);
    }

    /**
     * create a fake VCF record
     * @param header the VCF header
     * @return a VCFRecord
     */
    private VariantContext createVC(VCFHeader header) {

        GenomeLoc loc = genomeLocParser.createGenomeLoc("chr1",1);
        List<Allele> alleles = new ArrayList<Allele>();
        Set<String> filters = null;
        Map<String, Object> attributes = new HashMap<String,Object>();
        GenotypesContext genotypes = GenotypesContext.create(header.getGenotypeSamples().size());

        alleles.add(Allele.create("-",true));
        alleles.add(Allele.create("CC",false));

        attributes.put("DP","50");
        for (String name : header.getGenotypeSamples()) {
            Genotype gt = new GenotypeBuilder(name,alleles.subList(1,2)).GQ(0).attribute("BB", "1").phased(true).make();
            genotypes.add(gt);
        }
        return new VariantContextBuilder("RANDOM", loc.getContig(), loc.getStart(), loc.getStop(), alleles)
                .genotypes(genotypes).attributes(attributes).referenceBaseForIndel((byte)'A').make();
    }


    /**
     * validate a VCF header
     * @param header the header to validate
     */
    public void validateHeader(VCFHeader header) {
        // check the fields
        int index = 0;
        for (VCFHeader.HEADER_FIELDS field : header.getHeaderFields()) {
            Assert.assertEquals(VCFHeader.HEADER_FIELDS.values()[index], field);
            index++;
        }
        Assert.assertEquals(header.getMetaData().size(), metaData.size());
        index = 0;
        for (String key : header.getGenotypeSamples()) {
            Assert.assertTrue(additionalColumns.contains(key));
            index++;
        }
        Assert.assertEquals(index, additionalColumns.size());
    }
}
