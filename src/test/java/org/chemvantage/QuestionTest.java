package org.chemvantage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Question entity business logic.
 * Tests scoring, validation, and question type handling without database dependencies.
 */
class QuestionTest {

    private Question multipleChoiceQuestion;
    private Question numericQuestion;
    private Question fillInWordQuestion;

    @BeforeEach
    void setUp() {
        // Multiple Choice Question
        multipleChoiceQuestion = new Question(Question.MULTIPLE_CHOICE);
        multipleChoiceQuestion.text = "What is the atomic number of Carbon?";
        List<String> choices = new ArrayList<>();
        choices.add("4");
        choices.add("6");
        choices.add("8");
        choices.add("12");
        multipleChoiceQuestion.choices = choices;
        multipleChoiceQuestion.nChoices = 4;
        multipleChoiceQuestion.correctAnswer = "b";
        multipleChoiceQuestion.pointValue = 1;

        // Numeric Question
        numericQuestion = new Question(Question.NUMERIC);
        numericQuestion.text = "What is 2 + 2?";
        numericQuestion.correctAnswer = "4";
        numericQuestion.requiredPrecision = 0.0; // exact answer
        numericQuestion.significantFigures = 0;
        numericQuestion.pointValue = 1;

        // Fill In Word Question
        fillInWordQuestion = new Question(Question.FILL_IN_WORD);
        fillInWordQuestion.text = "The element with symbol 'O' is _____";
        fillInWordQuestion.correctAnswer = "oxygen";
        fillInWordQuestion.pointValue = 1;
    }

    @Test
    @DisplayName("Multiple choice question with correct answer should return true")
    void testMultipleChoiceCorrectAnswer() {
        assertTrue(multipleChoiceQuestion.isCorrect("b"));
    }

    @Test
    @DisplayName("Multiple choice question with wrong answer should return false")
    void testMultipleChoiceWrongAnswer() {
        assertFalse(multipleChoiceQuestion.isCorrect("a"));
        assertFalse(multipleChoiceQuestion.isCorrect("c"));
    }

    @Test
    @DisplayName("Numeric question with exact answer should return true")
    void testNumericExactAnswer() {
        assertTrue(numericQuestion.isCorrect("4"));
        assertTrue(numericQuestion.isCorrect("4.0"));
    }

    @Test
    @DisplayName("Numeric question with wrong answer should return false")
    void testNumericWrongAnswer() {
        assertFalse(numericQuestion.isCorrect("5"));
        assertFalse(numericQuestion.isCorrect("3.9"));
    }

    @Test
    @DisplayName("Fill-in-word question should be case insensitive")
    void testFillInWordCaseInsensitive() {
        assertTrue(fillInWordQuestion.isCorrect("oxygen"));
        assertTrue(fillInWordQuestion.isCorrect("Oxygen"));
        assertTrue(fillInWordQuestion.isCorrect("OXYGEN"));
    }

    @Test
    @DisplayName("Fill-in-word question should reject wrong answer")
    void testFillInWordWrongAnswer() {
        assertFalse(fillInWordQuestion.isCorrect("nitrogen"));
        assertFalse(fillInWordQuestion.isCorrect("hydrogen"));
    }

    @Test
    @DisplayName("Question type conversion should work correctly")
    void testQuestionTypeConversion() {
        assertEquals(1, Question.getQuestionType("MULTIPLE_CHOICE"));
        assertEquals(2, Question.getQuestionType("TRUE_FALSE"));
        assertEquals(3, Question.getQuestionType("SELECT_MULTIPLE"));
        assertEquals(4, Question.getQuestionType("FILL_IN_WORD"));
        assertEquals(5, Question.getQuestionType("NUMERIC"));
        assertEquals(6, Question.getQuestionType("FIVE_STAR"));
        assertEquals(7, Question.getQuestionType("ESSAY"));
        assertEquals(0, Question.getQuestionType("INVALID"));
    }

    @Test
    @DisplayName("Question type string conversion should work correctly")
    void testQuestionTypeStringConversion() {
        assertEquals("MULTIPLE_CHOICE", Question.getQuestionType(1));
        assertEquals("TRUE_FALSE", Question.getQuestionType(2));
        assertEquals("NUMERIC", Question.getQuestionType(5));
        assertEquals("", Question.getQuestionType(99));
    }

    @Test
    @DisplayName("Point value should default to 1")
    void testPointValueDefault() {
        Question q = new Question(Question.MULTIPLE_CHOICE);
        assertEquals(1, q.getPointValue());
    }

    @Test
    @DisplayName("Validate fields should handle null values")
    void testValidateFields() {
        Question q = new Question();
        q.text = null;
        q.type = null;
        q.correctAnswer = null;
        
        q.validateFields();
        
        assertNotNull(q.text);
        assertNotNull(q.type);
        assertNotNull(q.correctAnswer);
        assertEquals("", q.text);
        assertEquals("", q.type);
        assertEquals("", q.correctAnswer);
    }

    @Test
    @DisplayName("Numeric question with required precision should accept answers within tolerance")
    void testNumericWithPrecision() {
        Question q = new Question(Question.NUMERIC);
        q.text = "What is pi?";
        q.correctAnswer = "3.14159";
        q.requiredPrecision = 2.0; // 2% tolerance
        q.significantFigures = 3;
        q.pointValue = 1;

        // Within 2% should be correct
        assertTrue(q.isCorrect("3.14"));
        assertTrue(q.isCorrect("3.15"));
        
        // Outside 2% should be incorrect
        assertFalse(q.isCorrect("3.0"));
        assertFalse(q.isCorrect("3.5"));
    }

    @Test
    @DisplayName("OnLoad should set null fields to defaults")
    void testOnLoadDefaults() {
        Question q = new Question();
        q.nChoices = null;
        q.significantFigures = null;
        q.pointValue = null;
        q.parameters = null;
        q.choices = null;
        
        q.onLoad();
        
        assertEquals(0, q.nChoices);
        assertEquals(0, q.significantFigures);
        assertEquals(1, q.pointValue);
        assertNotNull(q.parameters);
        assertNotNull(q.choices);
    }

    @Test
    @DisplayName("Parameterized questions should require parser")
    void testRequiresParser() {
        Question q1 = new Question(Question.MULTIPLE_CHOICE);
        q1.text = "Normal question";
        q1.parameterString = "";
        assertFalse(q1.requiresParser());

        Question q2 = new Question(Question.MULTIPLE_CHOICE);
        q2.text = "Question with #a parameter";
        q2.parameterString = "";
        assertTrue(q2.requiresParser());

        Question q3 = new Question(Question.NUMERIC);
        q3.text = "Normal question";
        q3.parameterString = "a 1:5 b 2:8";
        assertTrue(q3.requiresParser());
    }
}
