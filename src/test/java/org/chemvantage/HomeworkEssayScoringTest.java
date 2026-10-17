package org.chemvantage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.HtmlUtils;
import com.google.gson.JsonObject;

class HomeworkEssayScoringTest {

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4, 5})
	void extractsScoreAndEscapedFeedback(int value) throws IOException {
		String response = "{\"score\":" + value
				+ ",\"feedback\":\"Explain \\\"moles\\\".\\nUse H<sub>2</sub>O correctly.\"}";
		JsonObject score = Homework.extractEssayScore(response);

		assertEquals(value, score.get("score").getAsInt());
		assertEquals("Explain \"moles\".\nUse H<sub>2</sub>O correctly.",
				HtmlUtils.htmlUnescape(score.get("feedback").getAsString()));
	}

	@Test
	void preservesFeedbackHtmlAndRemovesUnsafeMarkup() throws IOException {
		JsonObject score = Homework.extractEssayScore("""
				{"score":4,"feedback":"<p>Explain <strong>moles</strong> using H<sub>2</sub>O.</p><script>alert(1)</script><img src=x onerror=alert(1)>"}
				""");

		assertEquals("<p>Explain <strong>moles</strong> using H<sub>2</sub>O.</p>",
				score.get("feedback").getAsString());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "not JSON", "null", "[]", "{}",
			"{\"feedback\":\"Try again.\"}",
			"{\"score\":4}", "{\"score\":null,\"feedback\":\"Try again.\"}",
			"{\"score\":\"4\",\"feedback\":\"Try again.\"}",
			"{\"score\":true,\"feedback\":\"Try again.\"}",
			"{\"score\":4.5,\"feedback\":\"Try again.\"}",
			"{\"score\":0,\"feedback\":\"Try again.\"}",
			"{\"score\":6,\"feedback\":\"Try again.\"}",
			"{\"score\":99999999999999999999,\"feedback\":\"Try again.\"}",
			"{\"score\":4,\"feedback\":null}", "{\"score\":4,\"feedback\":5}",
			"{\"score\":4,\"feedback\":\" \"}",
			"{\"score\":4,\"feedback\":\"<script>alert(1)</script>\"}"})
	void rejectsInvalidGradingResponses(String response) {
		assertThrows(IOException.class, () -> Homework.extractEssayScore(response));
	}
}
