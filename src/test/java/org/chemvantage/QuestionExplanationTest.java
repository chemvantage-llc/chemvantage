package org.chemvantage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.util.HtmlUtils;

class QuestionExplanationTest {
	private static final String UNAVAILABLE = "Sorry, an explanation is not available at this time.";

	@Test
	void returnsCachedExplanationWithoutCallingGemini() {
		StubQuestion question = new StubQuestion();
		question.explanation = "<p>Stored explanation.</p>";

		assertEquals(question.explanation, question.getExplanation());
		assertEquals(0, question.requests);
	}

	@Test
	void regeneratesParameterizedExplanationAndSanitizesHtml() {
		StubQuestion question = new StubQuestion();
		question.parameterString = "a 1:5";
		question.explanation = "An explanation for different parameters.";
		question.response = "{\"explanation\":\"<p>Water is H<sub>2</sub>O.</p><script>alert(1)</script>\"}";

		assertEquals("<p>Water is H<sub>2</sub>O.</p>", question.getExplanation());
		assertEquals("<p>Water is H<sub>2</sub>O.</p>", question.getExplanation());
		assertEquals(2, question.requests);
		assertEquals("An explanation for different parameters.", question.explanation);
	}

	@Test
	void preservesJsonEscapedLatexThroughParsingAndSanitization() {
		StubQuestion question = new StubQuestion();
		question.parameterString = "a 1:5";
		question.response = """
				{"explanation":"<p>Use \\\\(n = \\\\frac{m}{M}\\\\).</p><p>\\\\[\\\\begin{aligned}n &amp;= 2\\\\,\\\\text{mol} \\\\\\\\ m &amp;= nM\\\\end{aligned}\\\\]</p><script>alert(1)</script>"}
				""";

		assertEquals("<p>Use \\(n = \\frac{m}{M}\\).</p>"
				+ "<p>\\[\\begin{aligned}n &= 2\\,\\text{mol} \\\\ m &= nM\\end{aligned}\\]</p>",
				HtmlUtils.htmlUnescape(question.getExplanation()));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "not JSON", "[]", "null", "{}", "{\"explanation\":null}",
			"{\"explanation\":5}", "{\"explanation\":\" \"}", "{\"explanation\":\"<script>alert(1)</script>\"}"})
	void rejectsInvalidResponsesWithoutCaching(String response) {
		StubQuestion question = new StubQuestion();
		question.response = response;

		assertEquals(UNAVAILABLE, question.getExplanation());
		assertNull(question.explanation);
	}

	@Test
	void reportsServiceFailureWithoutCaching() {
		StubQuestion question = new StubQuestion();
		question.failure = new IOException("Service unavailable");

		assertEquals(UNAVAILABLE, question.getExplanation());
		assertNull(question.explanation);
	}

	private static class StubQuestion extends Question {
		String response;
		IOException failure;
		int requests;

		StubQuestion() {
			super(Question.MULTIPLE_CHOICE);
			text = "What is the chemical formula of water?";
		}

		@Override
		String requestExplanationFromGemini() throws IOException {
			requests++;
			if (failure != null) throw failure;
			return response;
		}
	}
}
