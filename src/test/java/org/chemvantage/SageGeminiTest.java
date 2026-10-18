package org.chemvantage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class SageGeminiTest {
	@Test
	void returnsCachedHelpWithoutRequestingGemini() throws Exception {
		Question question = new Question(Question.ESSAY);
		question.sageAdvice = "<p>Stored hint.</p>";
		try (MockedStatic<Sage> sage = mockStatic(Sage.class, CALLS_REAL_METHODS)) {
			assertEquals(question.sageAdvice, Sage.getHelp(question));
			sage.verify(() -> Sage.requestHelpFromGemini(any()), never());
		}
	}

	@Test
	void generatesFreshParameterizedHintsWithoutCaching() throws Exception {
		Question question = new Question(Question.ESSAY);
		question.text = "How many moles?";
		question.parameterString = "a 1:5";
		try (MockedStatic<Sage> sage = mockStatic(Sage.class, CALLS_REAL_METHODS)) {
			sage.when(() -> Sage.requestSageContentFromGemini(anyString(), anyMap()))
					.thenAnswer(invocation -> {
						String prompt = invocation.getArgument(0);
						assertTrue(prompt.contains(question.printForSage()));
						assertTrue(prompt.contains("do not give the correct answer"));
						return "{\"answer\":\"<p>Consider molar mass.</p><script>alert(1)</script>\"}";
					});
			assertEquals("<p>Consider molar mass.</p>", Sage.getHelp(question));
			assertEquals("<p>Consider molar mass.</p>", Sage.getHelp(question));
			assertNull(question.sageAdvice);
			sage.verify(() -> Sage.requestHelpFromGemini(question), times(2));
		}
	}

	@Test
	void propagatesHintServiceFailuresWithoutCaching() {
		Question question = new Question(Question.ESSAY);
		try (MockedStatic<Sage> sage = mockStatic(Sage.class, CALLS_REAL_METHODS)) {
			sage.when(() -> Sage.requestHelpFromGemini(question)).thenThrow(new IOException("Unavailable"));
			assertThrows(IOException.class, () -> Sage.getHelp(question));
			assertNull(question.sageAdvice);
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 3, 4, 5})
	void scoresEssaysOnSageScaleAndSanitizesFeedback(int score) throws Exception {
		try (MockedStatic<Sage> sage = mockStatic(Sage.class, CALLS_REAL_METHODS)) {
			sage.when(() -> Sage.requestSageContentFromGemini(anyString(), anyMap()))
					.thenAnswer(invocation -> {
						String prompt = invocation.getArgument(0);
						assertTrue(prompt.contains("\"question_item\":\"Explain moles.\""));
						assertTrue(prompt.contains("\"student_answer\":\"" + "a".repeat(800) + "\""));
						return "{\"score\":" + score + ",\"feedback\":\"<p>Review units.</p><script>alert(1)</script>\"}";
					});
			var result = Sage.scoreEssayQuestion("Explain moles.", "a".repeat(801));
			assertEquals(score, result.get("score").getAsInt());
			assertEquals("<p>Review units.</p>", result.get("feedback").getAsString());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{}", "{\"score\":-1,\"feedback\":\"Review.\"}",
			"{\"score\":6,\"feedback\":\"Review.\"}", "{\"score\":2.5,\"feedback\":\"Review.\"}",
			"{\"score\":\"3\",\"feedback\":\"Review.\"}", "{\"score\":3,\"feedback\":\" \"}",
			"{\"score\":3,\"feedback\":\"<script>alert(1)</script>\"}"})
	void rejectsInvalidGradesInsteadOfAssigningZero(String response) {
		try (MockedStatic<Sage> sage = mockStatic(Sage.class, CALLS_REAL_METHODS)) {
			sage.when(() -> Sage.requestSageContentFromGemini(anyString(), anyMap())).thenReturn(response);
			assertThrows(IOException.class, () -> Sage.scoreEssayQuestion("Explain moles.", "My answer."));
		}
	}

	@Test
	void propagatesGradingServiceFailures() {
		try (MockedStatic<Sage> sage = mockStatic(Sage.class, CALLS_REAL_METHODS)) {
			sage.when(() -> Sage.requestSageContentFromGemini(anyString(), anyMap()))
					.thenThrow(new IOException("Unavailable"));
			assertThrows(IOException.class, () -> Sage.scoreEssayQuestion("Explain moles.", "My answer."));
		}
	}
}
