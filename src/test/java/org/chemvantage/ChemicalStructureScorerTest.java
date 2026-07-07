package org.chemvantage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.StringWriter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.io.MDLV2000Writer;
import org.openscience.cdk.layout.StructureDiagramGenerator;
import org.openscience.cdk.smiles.SmilesParser;

class ChemicalStructureScorerTest {

    @Test
    @DisplayName("Equivalent structures should match despite atom ordering")
    void testEquivalentStructuresMatch() throws Exception {
        String expected = molfileFromSmiles("CCO");
        String submitted = molfileFromSmiles("OCC");

        ChemicalStructureScorer.ComparisonResult result = ChemicalStructureScorer.compare(expected, submitted);

        assertTrue(result.matched());
        assertFalse(result.blankSubmission());
        assertFalse(result.parseError());
    }

    @Test
    @DisplayName("Bond order differences should not match")
    void testBondOrderMismatchFails() throws Exception {
        String expected = molfileFromSmiles("CCO");
        String submitted = molfileFromSmiles("C=CO");

        ChemicalStructureScorer.ComparisonResult result = ChemicalStructureScorer.compare(expected, submitted);

        assertFalse(result.matched());
        assertFalse(result.blankSubmission());
        assertFalse(result.parseError());
    }

    @Test
    @DisplayName("Connectivity differences should not match")
    void testConnectivityMismatchFails() throws Exception {
        String expected = molfileFromSmiles("CCO");
        String submitted = molfileFromSmiles("COC");

        ChemicalStructureScorer.ComparisonResult result = ChemicalStructureScorer.compare(expected, submitted);

        assertFalse(result.matched());
        assertFalse(result.blankSubmission());
        assertFalse(result.parseError());
    }

    @Test
    @DisplayName("Blank submissions should report a blank result")
    void testBlankSubmission() throws Exception {
        String expected = molfileFromSmiles("CCO");

        ChemicalStructureScorer.ComparisonResult result = ChemicalStructureScorer.compare(expected, "");

        assertFalse(result.matched());
        assertTrue(result.blankSubmission());
        assertFalse(result.parseError());
    }

    @Test
    @DisplayName("Invalid molfile text should report a parse error")
    void testInvalidMolfileSubmission() throws Exception {
        String expected = molfileFromSmiles("CCO");

        ChemicalStructureScorer.ComparisonResult result = ChemicalStructureScorer.compare(expected, "not a molfile");

        assertFalse(result.matched());
        assertFalse(result.blankSubmission());
        assertTrue(result.parseError());
    }

    @Test
    @DisplayName("Escaped quoted molfile text should still parse and match")
    void testEscapedQuotedMolfileSubmission() throws Exception {
        String expected = molfileFromSmiles("CCO");
        String submitted = "\"" + expected.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n") + "\"";

        ChemicalStructureScorer.ComparisonResult result = ChemicalStructureScorer.compare(expected, submitted);

        assertTrue(result.matched());
        assertFalse(result.blankSubmission());
        assertFalse(result.parseError());
    }

    @Test
    @DisplayName("Molfile with HTML newline entities should still parse and match")
    void testHtmlEntityNewlineMolfileSubmission() throws Exception {
        String expected = molfileFromSmiles("CCO");
        String submitted = expected.replace("\n", "&#10;");

        ChemicalStructureScorer.ComparisonResult result = ChemicalStructureScorer.compare(expected, submitted);

        assertTrue(result.matched());
        assertFalse(result.blankSubmission());
        assertFalse(result.parseError());
    }

    @Test
    @DisplayName("Molfile with unicode escaped newlines should still parse and match")
    void testUnicodeEscapedNewlineMolfileSubmission() throws Exception {
        String expected = molfileFromSmiles("CCO");
        String submitted = expected.replace("\n", "\\u000A");

        ChemicalStructureScorer.ComparisonResult result = ChemicalStructureScorer.compare(expected, submitted);

        assertTrue(result.matched());
        assertFalse(result.blankSubmission());
        assertFalse(result.parseError());
    }

    @Test
    @DisplayName("JSON-wrapped molfile payload should still parse and match")
    void testJsonWrappedMolfileSubmission() throws Exception {
        String expected = molfileFromSmiles("CCO");
        String escaped = expected.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
        String submitted = "{\"molfile\":\"" + escaped + "\",\"requestId\":\"sync-1\"}";

        ChemicalStructureScorer.ComparisonResult result = ChemicalStructureScorer.compare(expected, submitted);

        assertTrue(result.matched());
        assertFalse(result.blankSubmission());
        assertFalse(result.parseError());
    }

    @Test
    @DisplayName("Molfile wrapped with extra text should still parse and match")
    void testWrappedMolfileSubmission() throws Exception {
        String expected = molfileFromSmiles("CCO");
        String submitted = "payload-begin\n" + expected + "\npayload-end";

        ChemicalStructureScorer.ComparisonResult result = ChemicalStructureScorer.compare(expected, submitted);

        assertTrue(result.matched());
        assertFalse(result.blankSubmission());
        assertFalse(result.parseError());
    }

        @Test
        @DisplayName("Short-header INDIGO V2000 molfile should render and match")
        void testIndigoShortHeaderV2000Molfile() throws Exception {
                String indigoMolfile = """
-INDIGO-07052613332D

    3  2  0  0  0  0  0  0  0  0999 V2000
        3.4810   -4.0950    0.0000 C   0  0  0  0  0  0  0  0  0  0  0  0
        4.3471   -3.5950    0.0000 C   0  0  0  0  0  0  0  0  0  0  0  0
        2.6150   -3.5950    0.0000 O   0  0  0  0  0  0  0  0  0  0  0  0
    1  2  1  0  0  0  0
    1  3  2  0  0  0  0
M  END
""";

                String expected = molfileFromSmiles("CC=O");
                ChemicalStructureScorer.ComparisonResult result = ChemicalStructureScorer.compare(expected, indigoMolfile);
                String svg = ChemicalStructureScorer.renderSvg(indigoMolfile);

                assertTrue(result.matched());
                assertFalse(result.blankSubmission());
                assertFalse(result.parseError());
                assertFalse(svg.isEmpty());

                String prepared = ChemicalStructureScorer.prepareMolfileForEditor(indigoMolfile);
                ChemicalStructureScorer.ComparisonResult preparedResult = ChemicalStructureScorer.compare(expected, prepared);

                assertNotEquals("", prepared);
                assertTrue(prepared.contains("V2000"));
                assertTrue(preparedResult.matched());
                assertFalse(ChemicalStructureScorer.renderSvg(prepared).isEmpty());
        }

    private String molfileFromSmiles(String smiles) throws Exception {
        SmilesParser parser = new SmilesParser(DefaultChemObjectBuilder.getInstance());
        IAtomContainer molecule = parser.parseSmiles(smiles);
        StructureDiagramGenerator sdg = new StructureDiagramGenerator();
        sdg.setMolecule(molecule, false);
        sdg.generateCoordinates();

        StringWriter writer = new StringWriter();
        try (MDLV2000Writer mdlWriter = new MDLV2000Writer(writer)) {
            mdlWriter.write(sdg.getMolecule());
        }
        return writer.toString();
    }
}
