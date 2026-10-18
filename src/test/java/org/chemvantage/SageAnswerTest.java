package org.chemvantage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.util.HtmlUtils;

class SageAnswerTest {
	@Test
	void parsesAndSanitizesGeneratedAnswer() throws IOException {
		String response = "{\"answer\":\"<p>Water is H<sub>2</sub>O.</p><script>alert(1)</script>\"}";

		assertEquals("<p>Water is H<sub>2</sub>O.</p>", Sage.parseSageAnswer(response));
	}

	@Test
	void preservesJsonEscapedLatex() throws IOException {
		String response = """
				{"answer":"<p>Use \\\\(n = \\\\frac{m}{M}\\\\).</p><p>\\\\[n = 2\\\\,\\\\text{mol}\\\\]</p>"}
				""";

		assertEquals("<p>Use \\(n = \\frac{m}{M}\\).</p><p>\\[n = 2\\,\\text{mol}\\]</p>",
				HtmlUtils.htmlUnescape(Sage.parseSageAnswer(response)));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "{}", "{\"answer\":null}", "{\"answer\":5}", "{\"answer\":true}",
			"{\"answer\":[]}", "{\"answer\":\" \"}", "{\"answer\":\"<script>alert(1)</script>\"}"})
	void rejectsMissingOrInvalidAnswers(String response) {
		assertThrows(IOException.class, () -> Sage.parseSageAnswer(response));
	}

	@ParameterizedTest
	@ValueSource(strings = {"not JSON", "[]", "null"})
	void rejectsMalformedResponses(String response) {
		assertThrows(RuntimeException.class, () -> Sage.parseSageAnswer(response));
	}
}
