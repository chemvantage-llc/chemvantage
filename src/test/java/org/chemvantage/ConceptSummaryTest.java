package org.chemvantage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ConceptSummaryTest {
	private static final String UNAVAILABLE = "Sorry, Sage is unable to provide a summary of this concept at the moment.";

	@Test
	void returnsCachedSummaryWithoutCallingGemini() {
		StubConcept concept = new StubConcept();
		concept.summary = "<p>Stored summary.</p>";

		assertEquals(concept.summary, concept.getSummary());
		assertEquals(0, concept.requests);
	}

	@Test
	void parsesAndSanitizesGeneratedSummary() throws IOException {
		String response = "{\"summary\":\"<p>Water is H<sub>2</sub>O.</p><script>alert(1)</script>\"}";

		assertEquals("<p>Water is H<sub>2</sub>O.</p>", Concept.parseSummaryResponse(response));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "not JSON", "[]", "null", "{}", "{\"summary\":null}",
			"{\"summary\":5}", "{\"summary\":\" \"}", "{\"summary\":\"<script>alert(1)</script>\"}"})
	void rejectsInvalidResponsesWithoutCaching(String response) {
		StubConcept concept = new StubConcept();
		concept.response = response;

		assertEquals(UNAVAILABLE, concept.getSummary());
		assertNull(concept.summary);
	}

	@Test
	void reportsServiceFailureWithoutCaching() {
		StubConcept concept = new StubConcept();
		concept.failure = new IOException("Service unavailable");

		assertEquals(UNAVAILABLE, concept.getSummary());
		assertNull(concept.summary);
	}

	private static class StubConcept extends Concept {
		String response;
		IOException failure;
		int requests;

		StubConcept() {
			super("Water", "1");
		}

		@Override
		String requestSummaryFromGemini() throws IOException {
			requests++;
			if (failure != null) throw failure;
			return response;
		}
	}
}
