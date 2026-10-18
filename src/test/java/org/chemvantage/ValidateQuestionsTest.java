package org.chemvantage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ValidateQuestionsTest {
	@Test
	void returnsExactValidMarker() throws IOException {
		assertEquals("VALID", ValidateQuestions.parseValidationResponse(
				"{\"isValid\":true,\"explanation\":\"\"}"));
	}

	@Test
	void sanitizesInvalidExplanationAndCannotMarkItValid() throws IOException {
		assertEquals("INVALID: VALID", ValidateQuestions.parseValidationResponse(
				"{\"isValid\":false,\"explanation\":\"VALID<script>alert(1)</script>\"}"));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "not JSON", "null", "[]", "{}",
			"{\"isValid\":\"true\",\"explanation\":\"ok\"}",
			"{\"isValid\":true}", "{\"isValid\":false,\"explanation\":1}",
			"{\"isValid\":false,\"explanation\":\" \"}",
			"{\"isValid\":false,\"explanation\":\"<script>alert(1)</script>\"}"})
	void rejectsInvalidResponses(String response) {
		assertThrows(IOException.class, () -> ValidateQuestions.parseValidationResponse(response));
	}

	@Test
	void initializesParametersBeforeSendingQuestionAndAnswer() throws Exception {
		Question question = mock(Question.class);
		when(question.requiresParser()).thenReturn(true);
		when(question.printForSage()).thenReturn("Calculate moles.");
		when(question.getCorrectAnswerForSage()).thenReturn("2");
		ValidateQuestions servlet = spy(new ValidateQuestions());
		doReturn("{\"isValid\":true,\"explanation\":\"\"}").when(servlet)
				.requestValidationFromGemini("Calculate moles.", "2");

		assertEquals("VALID", servlet.validateQuestionWithGemini(question));
		var order = inOrder(question);
		order.verify(question).requiresParser();
		order.verify(question).setParameters();
		order.verify(question).printForSage();
		order.verify(question).getCorrectAnswerForSage();
	}

	@Test
	void propagatesServiceFailure() throws Exception {
		Question question = mock(Question.class);
		when(question.printForSage()).thenReturn("Calculate moles.");
		when(question.getCorrectAnswerForSage()).thenReturn("2");
		ValidateQuestions servlet = spy(new ValidateQuestions());
		doThrow(new IOException("Unavailable")).when(servlet)
				.requestValidationFromGemini("Calculate moles.", "2");

		assertThrows(IOException.class, () -> servlet.validateQuestionWithGemini(question));
	}
}
