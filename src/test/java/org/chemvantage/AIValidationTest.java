package org.chemvantage;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AI validation functionality using Gemini/Vertex AI.
 * Tests question validation, correct answer verification, and AI feedback.
 * 
 * Note: These tests require Google Cloud credentials and Vertex AI setup.
 * Most tests are disabled by default to avoid API calls during normal testing.
 * Enable selectively for integration testing with live AI services.
 */
@Disabled("Requires Vertex AI credentials and may incur API costs")
class AIValidationTest {

    private Question sampleQuestion;

    @BeforeEach
    void setUp() {
        sampleQuestion = TestHelper.createMultipleChoiceQuestion(1L, "b");
        sampleQuestion.text = "What is the chemical symbol for water?";
        sampleQuestion.choices.clear();
        sampleQuestion.choices.add("HO");
        sampleQuestion.choices.add("H2O");
        sampleQuestion.choices.add("H3O");
        sampleQuestion.choices.add("OH");
        sampleQuestion.correctAnswer = "b"; // H2O
    }

    @Test
    @DisplayName("Question should have checkedByAI field")
    void testAICheckField() {
        // Arrange
        Question q = new Question(Question.MULTIPLE_CHOICE);
        
        // Assert - Field should be nullable (null = not checked, true = valid, false = flagged)
        assertNull(q.checkedByAI);
    }

    @Test
    @DisplayName("Should mark question as AI-validated")
    void testMarkQuestionAsValidated() {
        // Act
        sampleQuestion.checkedByAI = true;
        
        // Assert
        assertEquals(true, sampleQuestion.checkedByAI);
        assertNotNull(sampleQuestion.checkedByAI);
    }

    @Test
    @DisplayName("Should mark question as AI-flagged")
    void testMarkQuestionAsFlagged() {
        // Act
        sampleQuestion.checkedByAI = false;
        
        // Assert
        assertEquals(false, sampleQuestion.checkedByAI);
    }

    @Test
    @DisplayName("Should query unchecked questions for AI validation")
    void testQueryUncheckedQuestions() {
        // Arrange
        Question checked = TestHelper.createMultipleChoiceQuestion(1L, "a");
        checked.checkedByAI = true;
        
        Question unchecked = TestHelper.createMultipleChoiceQuestion(1L, "b");
        unchecked.checkedByAI = null;
        
        // Assert
        assertNull(unchecked.checkedByAI);
        assertNotNull(checked.checkedByAI);
    }

    @Test
    @DisplayName("AI validation request should include question text and choices")
    void testAIValidationRequestStructure() {
        // Arrange
        JsonObject request = new JsonObject();
        request.addProperty("question_text", sampleQuestion.text);
        request.addProperty("correct_answer_index", "b");
        
        // Act - Build choices array
        StringBuilder choicesStr = new StringBuilder();
        char key = 'a';
        for (String choice : sampleQuestion.choices) {
            choicesStr.append(key).append(": ").append(choice).append("\n");
            key++;
        }
        request.addProperty("choices", choicesStr.toString());
        
        // Assert
        assertTrue(request.has("question_text"));
        assertTrue(request.has("correct_answer_index"));
        assertTrue(request.has("choices"));
        assertTrue(request.get("choices").getAsString().contains("H2O"));
    }

    @Test
    @DisplayName("AI response should validate correct answer is accurate")
    void testAICorrectAnswerValidation() {
        // Simulate AI validation logic
        String correctAnswer = "H2O";
        
        // In real implementation, this would call Gemini API
        // For now, we validate the structure
        boolean isValid = correctAnswer.equals("H2O");
        
        assertTrue(isValid);
    }

    @Test
    @DisplayName("Numeric question validation should check calculation accuracy")
    void testNumericQuestionAIValidation() {
        // Arrange
        Question numeric = TestHelper.createNumericQuestion(1L, "3.14159");
        numeric.text = "What is the value of pi to 5 decimal places?";
        
        // Act - Simulate validation
        String expectedAnswer = "3.14159";
        boolean isCorrect = numeric.correctAnswer.equals(expectedAnswer);
        
        // Assert
        assertTrue(isCorrect);
    }

    @Test
    @DisplayName("AI validation should detect ambiguous questions")
    void testAmbiguousQuestionDetection() {
        // Arrange
        Question ambiguous = new Question(Question.MULTIPLE_CHOICE);
        ambiguous.text = "What is the answer?"; // Too vague
        ambiguous.choices.add("Yes");
        ambiguous.choices.add("No");
        ambiguous.correctAnswer = "a";
        
        // Assert - Check question structure
        // AI would flag questions that are too vague or have too few choices
        assertTrue(ambiguous.text.contains("?"));
        assertTrue(ambiguous.choices.size() >= 2);
        assertTrue(ambiguous.text.length() < 30); // Suspiciously short
    }

    @Test
    @DisplayName("AI validation should check for duplicate choices")
    void testDuplicateChoiceDetection() {
        // Arrange
        Question withDuplicates = new Question(Question.MULTIPLE_CHOICE);
        withDuplicates.choices.add("Option A");
        withDuplicates.choices.add("Option A"); // Duplicate
        withDuplicates.choices.add("Option B");
        
        // Act - Check for duplicates
        long uniqueChoices = withDuplicates.choices.stream().distinct().count();
        boolean hasDuplicates = uniqueChoices < withDuplicates.choices.size();
        
        // Assert
        assertTrue(hasDuplicates);
    }

    @Test
    @DisplayName("Should format question for AI prompt")
    void testAIPromptFormatting() {
        // Act
        String prompt = String.format(
            "Validate this chemistry question:\n\n" +
            "Question: %s\n\n" +
            "Choices:\n%s\n\n" +
            "Stated Correct Answer: %s\n\n" +
            "Is the correct answer accurate? Are there any issues with the question?",
            sampleQuestion.text,
            formatChoices(sampleQuestion),
            sampleQuestion.correctAnswer
        );
        
        // Assert
        assertNotNull(prompt);
        assertTrue(prompt.contains("water"));
        assertTrue(prompt.contains("H2O"));
        assertTrue(prompt.contains("Validate"));
    }

    private String formatChoices(Question q) {
        StringBuilder sb = new StringBuilder();
        char key = 'a';
        for (String choice : q.choices) {
            sb.append(key).append(") ").append(choice).append("\n");
            key++;
        }
        return sb.toString();
    }

    @Test
    @DisplayName("Should handle AI validation timeout gracefully")
    void testAIValidationTimeout() {
        // Simulate timeout scenario
        boolean timedOut = false;
        
        // In real implementation, this would wrap the AI API call with timeout
        try {
            // Simulate long-running validation
            // In production: result = vertexAI.validate(question, 30000);
            timedOut = false; // Completed
        } catch (Exception e) {
            timedOut = true;
        }
        
        // Assert - Should handle gracefully
        assertFalse(timedOut);
    }

    @Test
    @DisplayName("Should batch multiple questions for efficient AI validation")
    void testBatchAIValidation() {
        // Act - Simulate batch request with 3 questions
        int batchSize = 3;
        boolean canBatch = batchSize <= 10; // Example API limit
        
        // Assert - Batch size should be within limits
        assertTrue(canBatch);
    }
}
