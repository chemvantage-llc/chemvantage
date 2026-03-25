package org.chemvantage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Assignment entity.
 * Tests assignment creation, validation, and configuration.
 */
class AssignmentTest {

    private Assignment quizAssignment;
    private Assignment homeworkAssignment;

    @BeforeEach
    void setUp() {
        quizAssignment = TestHelper.createTestAssignment("Quiz", "Chapter 1 Quiz");
        homeworkAssignment = TestHelper.createTestAssignment("Homework", "Chapter 1 Homework");
    }

    @Test
    @DisplayName("Quiz assignment should have correct type")
    void testQuizAssignmentType() {
        assertEquals("Quiz", quizAssignment.assignmentType);
        assertEquals("Chapter 1 Quiz", quizAssignment.title);
    }

    @Test
    @DisplayName("Homework assignment should have correct type")
    void testHomeworkAssignmentType() {
        assertEquals("Homework", homeworkAssignment.assignmentType);
        assertEquals("Chapter 1 Homework", homeworkAssignment.title);
    }

    @Test
    @DisplayName("Assignment should initialize with empty question keys")
    void testQuestionKeysInitialization() {
        assertNotNull(quizAssignment.questionKeys);
        assertTrue(quizAssignment.questionKeys.isEmpty());
    }

    @Test
    @DisplayName("Assignment should initialize with concept IDs")
    void testConceptIdsInitialization() {
        assertNotNull(quizAssignment.conceptIds);
        assertFalse(quizAssignment.conceptIds.isEmpty());
        assertEquals(2, quizAssignment.conceptIds.size());
    }

    @Test
    @DisplayName("Assignment time allowed should default to 15 minutes")
    void testDefaultTimeAllowed() {
        assertEquals(900, quizAssignment.timeAllowed); // 15 minutes in seconds
    }

    @Test
    @DisplayName("Assignment should accept custom time limits")
    void testCustomTimeAllowed() {
        quizAssignment.timeAllowed = 1800; // 30 minutes
        assertEquals(1800, quizAssignment.timeAllowed);
        
        quizAssignment.timeAllowed = 3600; // 60 minutes (maximum)
        assertEquals(3600, quizAssignment.timeAllowed);
    }

    @Test
    @DisplayName("Assignment attempts allowed should be nullable for unlimited")
    void testAttemptsAllowedNullable() {
        quizAssignment.attemptsAllowed = null;
        assertNull(quizAssignment.attemptsAllowed);
        
        quizAssignment.attemptsAllowed = 3;
        assertEquals(3, quizAssignment.attemptsAllowed);
    }

    @Test
    @DisplayName("Assignment should have topic and chapter references")
    void testTopicAndChapterReferences() {
        assertEquals(1L, quizAssignment.topicId);
        assertEquals(1L, quizAssignment.textId);
        assertEquals(1, quizAssignment.chapterNumber);
    }

    @Test
    @DisplayName("Assignment should handle LTI lineitem URL")
    void testLTILineitemURL() {
        String lineitemUrl = "https://platform.example.com/api/lti/courses/123/line_items/456";
        quizAssignment.lti_ags_lineitem_url = lineitemUrl;
        
        assertEquals(lineitemUrl, quizAssignment.lti_ags_lineitem_url);
        assertNotNull(quizAssignment.lti_ags_lineitem_url);
    }

    @Test
    @DisplayName("Assignment should handle membership URL for grade sync")
    void testMembershipURL() {
        String membershipUrl = "https://platform.example.com/api/lti/courses/123/memberships";
        quizAssignment.lti_nrps_context_memberships_url = membershipUrl;
        
        assertEquals(membershipUrl, quizAssignment.lti_nrps_context_memberships_url);
        assertNotNull(quizAssignment.lti_nrps_context_memberships_url);
    }

    @Test
    @DisplayName("New assignment should have zero ID before persistence")
    void testNewAssignmentId() {
        Assignment newAssignment = new Assignment();
        assertNull(newAssignment.id);
    }
}
