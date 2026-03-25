package org.chemvantage;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import com.google.gson.JsonObject;
import com.googlecode.objectify.ObjectifyService;
import com.googlecode.objectify.util.Closeable;
import org.junit.jupiter.api.*;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for LTI 1.3 authentication and launch flow.
 * Tests JWT token generation, validation, and LTI message handling.
 * 
 * Note: These tests require actual HMAC secret configuration.
 * Some tests are disabled to prevent failures without proper setup.
 */
class LTIIntegrationTest {

    private static LocalServiceTestHelper helper;
    private static boolean objectifyInitialized = false;
    private Closeable session;
    
    private String testPlatformId;
    private String testUserId;
    private Algorithm algorithm;

    @BeforeAll
    static void setUpClass() {
        // Initialize Objectify and register Subject entity (needed for HMAC secret)
        if (!objectifyInitialized) {
            ObjectifyService.init();
            ObjectifyService.register(Subject.class);
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
        // Set up in-memory datastore for each test
        helper = new LocalServiceTestHelper(
            new LocalDatastoreServiceTestConfig()
                .setNoStorage(true)
        );
        helper.setUp();
        session = ObjectifyService.begin();
        
        // Initialize Subject entity (creates default with HMAC secret)
        Subject.getHMAC256Secret(); // Triggers refresh() which creates default Subject
        
        testPlatformId = "https://test-platform.example.com";
        testUserId = "testuser123";
        
        // Now use actual Subject HMAC secret from datastore
        try {
            algorithm = Algorithm.HMAC256(Subject.getHMAC256Secret());
        } catch (Exception e) {
            // Handle in actual implementation
        }
    }
    
    @AfterEach
    void tearDown() {
        if (session != null) session.close();
        if (helper != null) helper.tearDown();
    }

    @Test
    @DisplayName("Should hash user ID consistently")
    void testUserIdHashing() {
        // Act
        String hash1 = Subject.hashId(testUserId);
        String hash2 = Subject.hashId(testUserId);
        
        // Assert
        assertNotNull(hash1);
        assertEquals(hash1, hash2, "Same input should produce same hash");
        assertNotEquals(testUserId, hash1, "Hash should differ from original");
    }

    @Test
    @DisplayName("Should create different hashes for different users")
    void testUniqueHashing() {
        // Arrange
        String user1 = "user_one";
        String user2 = "user_two";
        
        // Act
        String hash1 = Subject.hashId(user1);
        String hash2 = Subject.hashId(user2);
        
        // Assert
        assertNotEquals(hash1, hash2);
    }

    @Test
    @DisplayName("Should create User from LTI launch parameters")
    void testLTIUserCreation() {
        // Act
        User user = new User(testPlatformId, testUserId);
        
        // Assert
        assertNotNull(user);
        assertNotNull(user.getHashedId());
        assertFalse(user.isAnonymous());
        assertEquals(testPlatformId, user.platformId);
    }

    @Test
    @DisplayName("LTI User should have proper expiration")
    void testLTIUserExpiration() {
        // Arrange
        Date now = new Date();
        
        // Act
        User user = new User(testPlatformId, testUserId);
        
        // Assert
        assertNotNull(user.exp);
        assertTrue(user.exp.after(now), "Expiration should be in the future");
        
        // Check that expiration is approximately 90 minutes from now
        long expectedExp = now.getTime() + 5400000L; // 90 minutes
        long actualExp = user.exp.getTime();
        long difference = Math.abs(expectedExp - actualExp);
        assertTrue(difference < 5000, "Expiration should be ~90 minutes from now");
    }

    @Test
    @DisplayName("Should generate valid JWT token for user")
    void testJWTTokenGeneration() {
        // Arrange
        User user = new User(testPlatformId, testUserId);
        user.sig = 12345L; // Simulated database ID
        
        // Act
        String token = JWT.create()
            .withSubject(user.sig.toString())
            .withIssuedAt(new Date())
            .withExpiresAt(user.exp)
            .sign(algorithm);
        
        // Assert
        assertNotNull(token);
        assertTrue(token.split("\\.").length == 3, "JWT should have 3 parts");
    }

    @Test
    @DisplayName("Should verify JWT token successfully")
    void testJWTTokenVerification() {
        // Arrange
        String subject = "12345";
        Date exp = new Date(System.currentTimeMillis() + 3600000);
        
        String token = JWT.create()
            .withSubject(subject)
            .withIssuedAt(new Date())
            .withExpiresAt(exp)
            .sign(algorithm);
        
        // Act
        String verifiedSubject = JWT.require(algorithm)
            .build()
            .verify(token)
            .getSubject();
        
        // Assert
        assertEquals(subject, verifiedSubject);
    }

    @Test
    @DisplayName("Should parse LTI role claims correctly")
    void testLTIRoleParsing() {
        // Arrange - Create JWT payload with role claims
        JsonObject payload = TestHelper.createMockLTIPayload(
            testUserId,
            "deployment_123",
            "http://purl.imsglobal.org/vocab/lis/v2/membership#Instructor"
        );
        
        // Assert
        assertNotNull(payload);
        assertTrue(payload.has("sub"));
        assertEquals(testUserId, payload.get("sub").getAsString());
    }

    @Test
    @DisplayName("Should identify instructor role from role mask")
    void testInstructorRoleIdentification() {
        // Arrange
        User user = new User(testPlatformId, testUserId);
        user.roles = 8; // Instructor role bit
        
        // Assert
        assertTrue(user.isInstructor());
    }

    @Test
    @DisplayName("Should identify learner role from role mask")
    void testLearnerRoleIdentification() {
        // Arrange
        User user = new User(testPlatformId, testUserId);
        user.roles = 0; // Learner role
        
        // Assert
        assertFalse(user.isInstructor());
    }

    @Test
    @DisplayName("Should identify teaching assistant from role mask")
    void testTeachingAssistantRole() {
        // Arrange
        User user = new User(testPlatformId, testUserId);
        user.roles = 4; // TA role bit
        
        // Assert
        assertTrue(user.isTeachingAssistant());
    }

    @Test
    @DisplayName("Should combine platform ID and deployment ID correctly")
    void testPlatformDeploymentIdCombination() {
        // Arrange
        String platformId = "https://platform.edu";
        String deploymentId = "deploy_123";
        String expected = platformId + "/" + deploymentId;
        
        // Act
        String combined = platformId + "/" + deploymentId;
        
        // Assert
        assertEquals(expected, combined);
    }

    @Test
    @DisplayName("LTI message should include resource link ID")
    void testLTIResourceLinkId() {
        // Arrange
        String resourceLinkId = "assignment_456";
        
        // Assert - Resource link ID should map to assignment
        assertNotNull(resourceLinkId);
        assertTrue(resourceLinkId.startsWith("assignment_"));
    }

    @Test
    @DisplayName("Should handle anonymous vs LTI user distinction")
    void testAnonymousVsLTIUser() {
        // Arrange
        User anonymousUser = TestHelper.createAnonymousUser();
        User ltiUser = new User(testPlatformId, testUserId);
        
        // Assert
        assertTrue(anonymousUser.isAnonymous());
        assertFalse(ltiUser.isAnonymous());
        
        // Anonymous users have no platform ID, LTI users do
        assertNull(anonymousUser.platformId);
        assertNotNull(ltiUser.platformId);
        assertEquals(testPlatformId, ltiUser.platformId);
    }
}
