package org.chemvantage;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Test utility class for creating test data and mock objects.
 * Provides factory methods for common test scenarios.
 */
public class TestHelper {

    /**
     * Creates a test User with typical LTI properties
     */
    public static User createTestUser(String platformId, String userId, int role) {
        User user = new User(platformId, userId);
        user.roles = role;
        user.assignmentId = 0L;
        return user;
    }

    /**
     * Creates a test instructor user
     */
    public static User createTestInstructor() {
        User user = new User("https://test-platform.example.com", "instructor123");
        user.roles = 8; // Instructor role
        return user;
    }

    /**
     * Creates a test learner user
     */
    public static User createTestLearner() {
        User user = new User("https://test-platform.example.com", "learner456");
        user.roles = 0; // Learner role
        return user;
    }

    /**
     * Creates a test anonymous user
     */
    public static User createAnonymousUser() {
        return new User(); // Default anonymous user with 90 minute expiration
    }

    /**
     * Creates a test multiple choice Question
     */
    public static Question createMultipleChoiceQuestion(Long conceptId, String correctAnswer) {
        Question q = new Question(Question.MULTIPLE_CHOICE);
        q.conceptId = conceptId;
        q.text = "Test multiple choice question?";
        q.assignmentType = "Quiz";
        
        List<String> choices = new ArrayList<>();
        choices.add("Choice A");
        choices.add("Choice B");
        choices.add("Choice C");
        choices.add("Choice D");
        q.choices = choices;
        q.nChoices = 4;
        
        q.correctAnswer = correctAnswer;
        q.pointValue = 1;
        q.isActive = true;
        
        return q;
    }

    /**
     * Creates a test numeric Question
     */
    public static Question createNumericQuestion(Long conceptId, String correctAnswer) {
        Question q = new Question(Question.NUMERIC);
        q.conceptId = conceptId;
        q.text = "Calculate the answer:";
        q.assignmentType = "Quiz";
        q.correctAnswer = correctAnswer;
        q.requiredPrecision = 2.0; // 2% tolerance
        q.significantFigures = 3;
        q.pointValue = 1;
        q.isActive = true;
        
        return q;
    }

    /**
     * Creates a test Assignment
     */
    public static Assignment createTestAssignment(String type, String title) {
        Assignment a = new Assignment();
        a.assignmentType = type;
        a.title = title;
        a.topicId = 1L;
        a.textId = 1L;
        a.chapterNumber = 1;
        a.conceptIds = new ArrayList<>();
        a.conceptIds.add(1L);
        a.conceptIds.add(2L);
        a.questionKeys = new ArrayList<>();
        a.timeAllowed = 900; // 15 minutes
        
        return a;
    }

    /**
     * Creates a test QuizTransaction
     */
    public static QuizTransaction createTestQuizTransaction(Long assignmentId, String userId) {
        QuizTransaction qt = new QuizTransaction(assignmentId, userId);
        qt.downloaded = new Date();
        
        return qt;
    }

    /**
     * Creates a test Concept
     */
    public static Concept createTestConcept(Long id, String title) {
        Concept c = new Concept();
        c.id = id;
        c.title = title;
        c.orderBy = "10";
        
        return c;
    }

    /**
     * Creates a mock LTI JWT payload for testing
     */
    public static JsonObject createMockLTIPayload(String userId, String deploymentId, String role) {
        JsonObject payload = new JsonObject();
        payload.addProperty("sub", userId);
        payload.addProperty("https://purl.imsglobal.org/spec/lti/claim/deployment_id", deploymentId);
        
        JsonObject roles = new JsonObject();
        roles.addProperty("role", role);
        payload.add("https://purl.imsglobal.org/spec/lti/claim/roles", roles);
        
        payload.addProperty("iss", "https://test-platform.example.com");
        payload.addProperty("aud", "chemvantage_client_id");
        payload.addProperty("exp", System.currentTimeMillis() / 1000 + 3600); // 1 hour from now
        payload.addProperty("iat", System.currentTimeMillis() / 1000);
        
        return payload;
    }

    /**
     * Creates a test Deployment (simplified without field references that don't exist)
     */
    public static Deployment createTestDeployment() {
        Deployment d = new Deployment();
        // Note: Actual Deployment fields may vary - adjust as needed
        // This is a simplified version for testing
        return d;
    }

    /**
     * Simulates a POST request parameter map
     */
    public static java.util.Map<String, String[]> createParameterMap() {
        return new java.util.HashMap<>();
    }
}
