package org.chemvantage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for User entity and token management.
 * Tests user ID hashing, anonymous users, and role management.
 */
class UserTest {

    @Test
    @DisplayName("Anonymous user should have valid expiration-based ID")
    void testAnonymousUserCreation() {
        User anonymousUser = new User(); // Default anonymous user
        
        assertNotNull(anonymousUser.getId());
        assertTrue(anonymousUser.isAnonymous());
        assertFalse(anonymousUser.isInstructor());
    }

    @Test
    @DisplayName("Anonymous user with specific expiration should be created")
    void testAnonymousUserWithExpiration() {
        long future = new Date().getTime() + 5400000L; // 90 minutes from now
        User anonymousUser = new User(future);
        
        assertNotNull(anonymousUser.getId());
        assertTrue(anonymousUser.isAnonymous());
    }

    @Test
    @DisplayName("LTI user should have hashed ID")
    void testLTIUserHashedId() {
        String platformId = "https://platform.example.com";
        String userId = "user123";
        
        User user = new User(platformId, userId);
        
        assertNotNull(user.getHashedId());
        assertFalse(user.isAnonymous());
    }

    @Test
    @DisplayName("Subject hashId should be consistent for same input")
    void testSubjectHashIdConsistency() {
        String input = "testuser123";
        
        String hashedId1 = Subject.hashId(input);
        String hashedId2 = Subject.hashId(input);
        
        assertNotNull(hashedId1);
        assertEquals(hashedId1, hashedId2);
    }

    @Test
    @DisplayName("Subject hashId should produce different hashes for different inputs")
    void testSubjectHashIdUniqueness() {
        String input1 = "user1";
        String input2 = "user2";
        
        String hashedId1 = Subject.hashId(input1);
        String hashedId2 = Subject.hashId(input2);
        
        assertNotEquals(hashedId1, hashedId2);
    }

    @Test
    @DisplayName("User with instructor role should be identified as instructor")
    void testInstructorRole() {
        User user = new User();
        user.roles = 8; // Instructor role mask
        
        assertTrue(user.isInstructor());
    }

    @Test
    @DisplayName("User with learner role should not be instructor")
    void testLearnerRole() {
        User user = new User();
        user.roles = 0; // Learner role mask
        
        assertFalse(user.isInstructor());
    }

    @Test
    @DisplayName("User assignment ID should be stored and retrieved")
    void testAssignmentId() {
        User user = new User();
        user.assignmentId = 12345L;
        
        assertEquals(12345L, user.getAssignmentId());
    }

    @Test
    @DisplayName("Token signature should be generated for anonymous user")
    void testAnonymousTokenSignature() {
        User user = new User();
        
        String signature = user.getTokenSignature();
        
        assertNotNull(signature);
    }
}
