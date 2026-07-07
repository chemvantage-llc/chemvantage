package org.chemvantage;

import java.io.StringWriter;
import java.io.Reader;
import java.io.Serial;
import java.io.Serializable;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.aromaticity.Aromaticity;
import org.openscience.cdk.aromaticity.ElectronDonation;
import org.openscience.cdk.depict.DepictionGenerator;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.graph.Cycles;
import org.openscience.cdk.interfaces.IAtom;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.io.IChemObjectReader.Mode;
import org.openscience.cdk.io.MDLReader;
import org.openscience.cdk.io.MDLV2000Reader;
import org.openscience.cdk.io.MDLV2000Writer;
import org.openscience.cdk.io.MDLV3000Reader;
import org.openscience.cdk.layout.StructureDiagramGenerator;
import org.openscience.cdk.smiles.SmiFlavor;
import org.openscience.cdk.smiles.SmilesGenerator;
import org.openscience.cdk.tools.CDKHydrogenAdder;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;

public final class ChemicalStructureScorer {
	private static final SmilesGenerator CANONICAL_SMILES = new SmilesGenerator(SmiFlavor.Unique | SmiFlavor.UseAromaticSymbols);
	private static final String EMPTY_STRUCTURE = "No structure data was submitted.";
	private static final String INVALID_STRUCTURE = "The submitted structure could not be parsed as a molfile.";
	private static final Pattern MOLFILE_JSON_PATTERN = Pattern.compile("\\\"molfile\\\"\\s*:\\s*\\\"(.*)\\\"", Pattern.DOTALL);

	private ChemicalStructureScorer() {}

	public static ComparisonResult compare(String expectedMolfile, String submittedMolfile) {
		if (submittedMolfile == null || submittedMolfile.isBlank()) {
			return new ComparisonResult(false, true, false, null, null, EMPTY_STRUCTURE);
		}
		if (expectedMolfile == null || expectedMolfile.isBlank()) {
			return new ComparisonResult(false, false, false, null, null, "This question does not have a stored reference structure.");
		}

		try {
			String expectedCanonical = canonicalize(expectedMolfile);
			String submittedCanonical = canonicalize(submittedMolfile);
			boolean matched = expectedCanonical.equals(submittedCanonical);
			String message = matched
					? "The submitted structure matches the expected connectivity and bond orders."
					: "The submitted structure does not match the expected connectivity and bond orders.";
			return new ComparisonResult(matched, false, false, expectedCanonical, submittedCanonical, message);
		} catch (Exception e) {
			return new ComparisonResult(false, false, true, null, null, INVALID_STRUCTURE);
		}
	}

	public static String canonicalize(String molfile) throws Exception {
		IAtomContainer molecule = parseMolfile(molfile);
		normalizeForComparison(molecule);
		return CANONICAL_SMILES.create(molecule);
	}

	public static String renderSvg(String molfile) {
		if (molfile == null || molfile.isBlank()) return "";
		try {
			IAtomContainer molecule = parseMolfile(molfile);
			StructureDiagramGenerator sdg = new StructureDiagramGenerator();
			sdg.setMolecule(molecule, false);
			sdg.generateCoordinates();
			return new DepictionGenerator()
					.withSize(320, 240)
					.withFillToFit()
					.depict(sdg.getMolecule())
					.toSvgStr();
		} catch (Exception e) {
			return "";
		}
	}

	public static String prepareMolfileForEditor(String molfile) {
		if (molfile == null || molfile.isBlank()) return "";
		try {
			IAtomContainer molecule = parseMolfile(molfile);
			StringWriter writer = new StringWriter();
			try (MDLV2000Writer mdlWriter = new MDLV2000Writer(writer)) {
				mdlWriter.write(molecule);
			}
			return normalizeMolfileForEditor(writer.toString());
		} catch (Exception e) {
			return normalizeMolfileForEditor(molfile);
		}
	}

	private static String normalizeMolfileForEditor(String molfile) {
		String normalized = normalizeMolfileInput(molfile);
		if (normalized == null || normalized.isBlank()) return "";

		String[] lines = normalized.split("\\n", -1);
		int countsLine = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].contains("V2000") || lines[i].contains("V3000") || lines[i].contains("M  V30 BEGIN CTAB")) {
				countsLine = i;
				break;
			}
		}
		if (countsLine < 0) return normalized;

		ArrayList<String> rebuilt = new ArrayList<>();
		rebuilt.add("  -INDIGO-00000000002D");
		rebuilt.add("");
		rebuilt.add("");
		for (int i = countsLine; i < lines.length; i++) rebuilt.add(lines[i]);
		return String.join("\n", rebuilt);
	}

	private static IAtomContainer parseMolfile(String molfile) throws Exception {
		String normalizedMolfile = normalizeMolfileInput(molfile);
		if (normalizedMolfile.isBlank()) throw new CDKException("No structure text was provided.");

		Exception lastError = null;
		if (normalizedMolfile.contains("V3000")) {
			try {
				return parseWithV3000(normalizedMolfile);
			} catch (Exception e) {
				lastError = e;
			}
		}

		try {
			return parseWithV2000(normalizedMolfile);
		} catch (Exception e) {
			lastError = e;
		}

		try {
			return parseWithV2000Relaxed(normalizedMolfile);
		} catch (Exception e) {
			lastError = e;
		}

		try {
			return parseWithLegacyMdl(normalizedMolfile);
		} catch (Exception e) {
			lastError = e;
		}

		if (!normalizedMolfile.contains("V3000")) {
			try {
				return parseWithV3000(normalizedMolfile);
			} catch (Exception e) {
				lastError = e;
			}
		}

		throw new CDKException("No atoms were parsed from the molfile.", lastError);
	}

	private static String normalizeMolfileInput(String molfile) {
		if (molfile == null) return "";
		String normalized = extractMolfileFromJsonIfPresent(molfile);
		normalized = decodeUnicodeNewlineEscapes(normalized);
		normalized = normalized.replace("\\\\\"", "\"");
		if (normalized.length() > 1 && normalized.startsWith("\"") && normalized.endsWith("\"")
				&& (normalized.contains("\\n") || normalized.contains("\\r") || normalized.contains("\\t"))) {
			normalized = normalized.substring(1, normalized.length() - 1);
		}
		normalized = normalized
				.replace("\\r\\n", "\n")
				.replace("\\n", "\n")
				.replace("\\r", "\n")
				.replace("\\t", "\t");
		normalized = normalized
				.replace("&#10;", "\n")
				.replace("&#13;", "\n")
				.replace("&#xA;", "\n")
				.replace("&#xD;", "\n")
				.replace("&#xa;", "\n")
				.replace("&#xd;", "\n")
				.replace("\r\n", "\n")
				.replace('\r', '\n')
				.replace("&amp;", "&")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&#39;", "'")
				.replace("&quot;", "\"");
		normalized = extractLikelyMolfileBlock(normalized);
		normalized = repairShortV2000Header(normalized);
		normalized = normalizeV2000CountsLine(normalized);
		normalized = normalizeV2000AtomAndBondLines(normalized);
		return normalized;
	}

	private static String extractMolfileFromJsonIfPresent(String text) {
		if (text == null) return "";
		String trimmed = text.trim();
		if (!(trimmed.startsWith("{") && trimmed.endsWith("}")) || !trimmed.contains("\"molfile\"")) return text;
		Matcher matcher = MOLFILE_JSON_PATTERN.matcher(trimmed);
		if (matcher.find()) {
			String captured = matcher.group(1);
			return captured == null ? text : captured;
		}
		return text;
	}

	private static String decodeUnicodeNewlineEscapes(String text) {
		if (text == null || text.isEmpty()) return text;
		return text
				.replace("\\u000A", "\n")
				.replace("\\u000a", "\n")
				.replace("\\u000D", "\n")
				.replace("\\u000d", "\n");
	}

	private static String extractLikelyMolfileBlock(String text) {
		if (text == null || text.isBlank()) return text;
		if (!text.contains("M  END")) return text;
		String[] lines = text.split("\\n", -1);
		int versionLine = -1;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.contains("V2000") || line.contains("V3000") || line.contains("M  V30 BEGIN CTAB")) {
				versionLine = i;
				break;
			}
		}
		if (versionLine < 0) return text;
		int start = Math.max(0, versionLine - 3);
		int end = lines.length - 1;
		for (int i = versionLine; i < lines.length; i++) {
			if (lines[i].contains("M  END")) {
				end = i;
				break;
			}
		}
		StringBuilder sb = new StringBuilder();
		for (int i = start; i <= end; i++) {
			if (i > start) sb.append('\n');
			sb.append(lines[i]);
		}
		return sb.toString();
	}

	private static String repairShortV2000Header(String text) {
		if (text == null || text.isBlank()) return text;
		String[] lines = text.split("\\n", -1);
		int v2000Line = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].contains("V2000")) {
				v2000Line = i;
				break;
			}
		}
		if (v2000Line < 0 || v2000Line >= 3) return text;

		ArrayList<String> repaired = new ArrayList<>();
		for (String line : lines) repaired.add(line);
		while (v2000Line < 3) {
			repaired.add(v2000Line, "");
			v2000Line++;
		}

		return String.join("\n", repaired);
	}

	private static String normalizeV2000CountsLine(String text) {
		if (text == null || text.isBlank() || !text.contains("V2000")) return text;
		String[] lines = text.split("\\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (!line.contains("V2000")) continue;
			Matcher matcher = Pattern.compile("\\s*(\\d+)\\s+(\\d+).*V2000").matcher(line);
			if (!matcher.find()) continue;
			try {
				int atoms = Integer.parseInt(matcher.group(1));
				int bonds = Integer.parseInt(matcher.group(2));
				lines[i] = String.format("%3d%3d  0  0  0  0  0  0  0  0999 V2000", atoms, bonds);
			} catch (Exception ignored) {
				// Keep original line if numeric parsing fails.
			}
			break;
		}
		return String.join("\n", lines);
	}

	private static String normalizeV2000AtomAndBondLines(String text) {
		if (text == null || text.isBlank() || !text.contains("V2000")) return text;
		String[] lines = text.split("\\n", -1);
		int countsLine = -1;
		int atoms = -1;
		int bonds = -1;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (!line.contains("V2000")) continue;
			Matcher matcher = Pattern.compile("\\s*(\\d+)\\s+(\\d+).*V2000").matcher(line);
			if (!matcher.find()) continue;
			try {
				atoms = Integer.parseInt(matcher.group(1));
				bonds = Integer.parseInt(matcher.group(2));
				countsLine = i;
				break;
			} catch (Exception ignored) {
				return text;
			}
		}
		if (countsLine < 0 || atoms < 0 || bonds < 0) return text;

		int atomStart = countsLine + 1;
		int atomEnd = atomStart + atoms - 1;
		int bondStart = atomEnd + 1;
		int bondEnd = bondStart + bonds - 1;
		if (bondEnd >= lines.length) return text;

		for (int i = atomStart; i <= atomEnd; i++) {
			String[] tokens = lines[i].trim().split("\\s+");
			if (tokens.length < 4) return text;
			try {
				double x = Double.parseDouble(tokens[0]);
				double y = Double.parseDouble(tokens[1]);
				double z = Double.parseDouble(tokens[2]);
				String symbol = tokens[3];
				lines[i] = String.format("%10.4f%10.4f%10.4f %-3s 0  0  0  0  0  0  0  0  0  0  0  0", x, y, z, symbol);
			} catch (Exception ignored) {
				return text;
			}
		}

		for (int i = bondStart; i <= bondEnd; i++) {
			String[] tokens = lines[i].trim().split("\\s+");
			if (tokens.length < 3) return text;
			try {
				int a1 = Integer.parseInt(tokens[0]);
				int a2 = Integer.parseInt(tokens[1]);
				int order = Integer.parseInt(tokens[2]);
				lines[i] = String.format("%3d%3d%3d  0  0  0  0", a1, a2, order);
			} catch (Exception ignored) {
				return text;
			}
		}

		return String.join("\n", lines);
	}

	private static IAtomContainer parseWithV2000(String molfile) throws Exception {
		try (Reader reader = new StringReader(molfile);
				MDLV2000Reader mdl = new MDLV2000Reader(reader)) {
			IAtomContainer molecule = mdl.read(DefaultChemObjectBuilder.getInstance().newAtomContainer());
			if (molecule == null || molecule.getAtomCount() == 0) throw new CDKException("V2000 reader parsed zero atoms.");
			return molecule;
		}
	}

	private static IAtomContainer parseWithV2000Relaxed(String molfile) throws Exception {
		try (Reader reader = new StringReader(molfile);
				MDLV2000Reader mdl = new MDLV2000Reader(reader, Mode.RELAXED)) {
			IAtomContainer molecule = mdl.read(DefaultChemObjectBuilder.getInstance().newAtomContainer());
			if (molecule == null || molecule.getAtomCount() == 0) throw new CDKException("Relaxed V2000 reader parsed zero atoms.");
			return molecule;
		}
	}

	private static IAtomContainer parseWithV3000(String molfile) throws Exception {
		try (Reader reader = new StringReader(molfile);
				MDLV3000Reader mdl = new MDLV3000Reader(reader)) {
			IAtomContainer molecule = mdl.read(DefaultChemObjectBuilder.getInstance().newAtomContainer());
			if (molecule == null || molecule.getAtomCount() == 0) throw new CDKException("V3000 reader parsed zero atoms.");
			return molecule;
		}
	}

	@SuppressWarnings("deprecation")
	private static IAtomContainer parseWithLegacyMdl(String molfile) throws Exception {
		try (Reader reader = new StringReader(molfile);
				MDLReader mdl = new MDLReader(reader)) {
			IAtomContainer molecule = mdl.read(DefaultChemObjectBuilder.getInstance().newAtomContainer());
			if (molecule == null || molecule.getAtomCount() == 0) throw new CDKException("Legacy MDL reader parsed zero atoms.");
			return molecule;
		}
	}

	@SuppressWarnings("deprecation")
	private static void normalizeForComparison(IAtomContainer molecule) throws Exception {
		AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(molecule);
		CDKHydrogenAdder.getInstance(DefaultChemObjectBuilder.getInstance()).addImplicitHydrogens(molecule);
		new Aromaticity(ElectronDonation.daylight(), Cycles.or(Cycles.all(), Cycles.relevant())).apply(molecule);
		AtomContainerManipulator.suppressHydrogens(molecule);
		molecule.setStereoElements(new ArrayList<>());
		for (IAtom atom : molecule.atoms()) {
			atom.setFormalCharge(0);
			atom.setMassNumber(null);
		}
	}

	public static final class ComparisonResult implements Serializable {
		@Serial
		private static final long serialVersionUID = 1L;

		private final boolean matched;
		private final boolean blankSubmission;
		private final boolean parseError;
		private final String expectedCanonical;
		private final String submittedCanonical;
		private final String message;

		ComparisonResult(boolean matched, boolean blankSubmission, boolean parseError, String expectedCanonical, String submittedCanonical, String message) {
			this.matched = matched;
			this.blankSubmission = blankSubmission;
			this.parseError = parseError;
			this.expectedCanonical = expectedCanonical;
			this.submittedCanonical = submittedCanonical;
			this.message = message;
		}

		public boolean matched() {
			return matched;
		}

		public boolean blankSubmission() {
			return blankSubmission;
		}

		public boolean parseError() {
			return parseError;
		}

		public String expectedCanonical() {
			return expectedCanonical;
		}

		public String submittedCanonical() {
			return submittedCanonical;
		}

		public String message() {
			return message;
		}
	}
}
