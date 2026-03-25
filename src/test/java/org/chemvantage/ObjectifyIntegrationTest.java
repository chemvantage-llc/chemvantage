package org.chemvantage;

import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.ObjectifyService;
import com.googlecode.objectify.util.Closeable;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static com.googlecode.objectify.ObjectifyService.ofy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Objectify datastore operations.
 * These tests use the App Engine Local Datastore Test Helper for in-memory testing.
 * 
 * Note: Tests execute sequentially to ensure proper datastore isolation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObjectifyIntegrationTest {

    private static LocalServiceTestHelper helper;
    private static boolean objectifyInitialized = false;
    private Closeable session;
    
    @BeforeAll
    static void setUpClass() {
        // Initialize Objectify once for all tests
        if (!objectifyInitialized) {
            ObjectifyService.init();
            ObjectifyService.register(Question.class);
            ObjectifyService.register(Assignment.class);
            ObjectifyService.register(User.class);
            ObjectifyService.register(QuizTransaction.class);
            ObjectifyService.register(Concept.class);
            objectifyInitialized = true;
        }
    }
    
    @AfterAll
    static void tearDownClass() {
        if (helper != null) {
            try {
                helper.tearDown();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @BeforeEach
    void setUp() {
        // Create fresh datastore for each test with no external data
        helper = new LocalServiceTestHelper(
            new LocalDatastoreServiceTestConfig()
                .setNoStorage(true) // Don't persist to disk
                .setDefaultHighRepJobPolicyUnappliedJobPercentage(0)
        );
        helper.setUp();
        
        session = ObjectifyService.begin();
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
        if (helper != null) {
            helper.tearDown();
            helper = null; // Ensure cleanup
        }
    }

    @Test
    @DisplayName("Should save and load Question entity from datastore")
    void testSaveAndLoadQuestion() {
        // Arrange
        Question q = TestHelper.createMultipleChoiceQuestion(1L, "b");
        
        // Act
        Key<Question> key = ofy().save().entity(q).now();
        Question loaded = ofy().load().key(key).now();
        
        // Assert
        assertNotNull(loaded);
        assertEquals(q.text, loaded.text);
        assertEquals(q.correctAnswer, loaded.correctAnswer);
        assertEquals(q.conceptId, loaded.conceptId);
        assertEquals(q.pointValue, loaded.pointValue);
    }

    @Test
    @DisplayName("Should query Questions by assignmentType and conceptId")
    void testQueryQuestionsByFilters() {
        // Arrange - Use unique text to identify our test questions
        Question q1 = TestHelper.createMultipleChoiceQuestion(1L, "a");
        q1.assignmentType = "Quiz";
        q1.text = "TEST_UNIQUE_Q1_" + System.nanoTime();
        
        Question q2 = TestHelper.createMultipleChoiceQuestion(1L, "b");
        q2.assignmentType = "Quiz";
        q2.text = "TEST_UNIQUE_Q2_" + System.nanoTime();
        
        Question q3 = TestHelper.createMultipleChoiceQuestion(2L, "c");
        q3.assignmentType = "Homework";
        q3.text = "TEST_UNIQUE_Q3_" + System.nanoTime();
        
        ofy().save().entities(q1, q2, q3).now();
        
        // Act
        List<Question> quizQuestions = ofy().load().type(Question.class)
            .filter("assignmentType", "Quiz")
            .filter("conceptId", 1L)
            .list();
        
        // Assert - Verify our specific questions are in the results
        assertTrue(quizQuestions.size() >= 2, "Should have at least our 2 test questions");
        assertTrue(quizQuestions.stream().anyMatch(q -> q.text.equals(q1.text)));
        assertTrue(quizQuestions.stream().anyMatch(q -> q.text.equals(q2.text)));
        assertFalse(quizQuestions.stream().anyMatch(q -> q.text.equals(q3.text)));
        assertTrue(quizQuestions.stream().allMatch(q -> q.assignmentType.equals("Quiz")));
        assertTrue(quizQuestions.stream().allMatch(q -> q.conceptId.equals(1L)));
    }

    @Test
    @DisplayName("Should update Question entity in datastore")
    void testUpdateQuestion() {
        // Arrange
        Question q = TestHelper.createMultipleChoiceQuestion(1L, "b");
        Key<Question> key = ofy().save().entity(q).now();
        
        // Act
        Question loaded = ofy().load().key(key).now();
        loaded.text = "Updated question text";
        loaded.pointValue = 2;
        ofy().save().entity(loaded).now();
        
        Question updated = ofy().load().key(key).now();
        
        // Assert
        assertEquals("Updated question text", updated.text);
        assertEquals(2, updated.pointValue);
    }

    @Test
    @DisplayName("Should delete Question entity from datastore")
    void testDeleteQuestion() {
        // Arrange
        Question q = TestHelper.createMultipleChoiceQuestion(1L, "b");
        Key<Question> key = ofy().save().entity(q).now();
        
        // Act
        ofy().delete().key(key).now();
        Question loaded = ofy().load().key(key).now();
        
        // Assert
        assertNull(loaded);
    }

    @Test
    @DisplayName("Should handle batch operations efficiently")
    void testBatchOperations() {
        // Arrange - Create questions with unique marker
        String uniqueMarker = "BATCH_TEST_" + System.nanoTime();
        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Question q = TestHelper.createMultipleChoiceQuestion(999L, "b"); // Use unique conceptId
            q.text = uniqueMarker + "_Q" + i;
            questions.add(q);
        }
        
        // Act - Save all in one batch
        ofy().save().entities(questions).now();
        
        // Assert - Verify our specific questions were saved
        List<Question> savedQuestions = ofy().load().type(Question.class)
            .filter("conceptId", 999L)
            .list();
        
        long ourQuestions = savedQuestions.stream()
            .filter(q -> q.text.startsWith(uniqueMarker))
            .count();
        
        assertEquals(10, ourQuestions, "Should have saved all 10 test questions");
    }

    @Test
    @DisplayName("Should save and load Assignment with question keys")
    void testAssignmentWithQuestionKeys() {
        // Arrange
        Assignment a = TestHelper.createTestAssignment("Quiz", "Chapter 1");
        
        // Create and save some questions
        Question q1 = TestHelper.createMultipleChoiceQuestion(1L, "a");
        Question q2 = TestHelper.createMultipleChoiceQuestion(1L, "b");
        Key<Question> key1 = ofy().save().entity(q1).now();
        Key<Question> key2 = ofy().save().entity(q2).now();
        
        a.questionKeys.add(key1);
        a.questionKeys.add(key2);
        
        // Act
        Key<Assignment> aKey = ofy().save().entity(a).now();
        Assignment loaded = ofy().load().key(aKey).now();
        
        // Assert
        assertNotNull(loaded);
        assertEquals(2, loaded.questionKeys.size());
        assertTrue(loaded.questionKeys.contains(key1));
        assertTrue(loaded.questionKeys.contains(key2));
    }

    @Test
    @DisplayName("Should handle QuizTransaction save and retrieval")
    void testQuizTransaction() {
        // Arrange
        String userId = Subject.hashId("testuser");
        QuizTransaction qt = new QuizTransaction(123L, userId);
        qt.score = 8;
        qt.putPossibleScore(10);
        
        // Act
        Key<QuizTransaction> key = ofy().save().entity(qt).now();
        QuizTransaction loaded = ofy().load().key(key).now();
        
        // Assert
        assertNotNull(loaded);
        assertEquals(8, loaded.score);
        assertEquals(10, loaded.possibleScore);
        assertEquals(userId, loaded.userId);
    }

    @Test
    @DisplayName("Should query QuizTransactions by userId and assignmentId")
    void testQueryQuizTransactions() {
        // Arrange - Use unique userId and assignmentId
        long uniqueAssignmentId = System.currentTimeMillis();
        String userId = Subject.hashId("testuser_" + System.nanoTime());
        
        QuizTransaction qt1 = new QuizTransaction(uniqueAssignmentId, userId);
        QuizTransaction qt2 = new QuizTransaction(uniqueAssignmentId, userId);
        QuizTransaction qt3 = new QuizTransaction(456L, userId); // Different assignment
        
        ofy().save().entities(qt1, qt2, qt3).now();
        
        // Act
        List<QuizTransaction> transactions = ofy().load().type(QuizTransaction.class)
            .filter("userId", userId)
            .filter("assignmentId", uniqueAssignmentId)
            .list();
        
        // Assert - Should find exactly our 2 transactions for this assignment
        assertEquals(2, transactions.size(), "Should find exactly 2 transactions for our unique assignment");
        assertTrue(transactions.stream().allMatch(t -> t.assignmentId == uniqueAssignmentId));
        assertTrue(transactions.stream().allMatch(t -> t.userId.equals(userId)));
    }
}
