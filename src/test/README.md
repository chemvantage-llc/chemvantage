# ChemVantage Test Suite

This directory contains unit and integration tests for the ChemVantage application.

## Test Structure

```
src/test/java/org/chemvantage/
├── QuestionTest.java              - Tests for Question entity logic (18 tests)
├── UserTest.java                  - Tests for User entity and authentication (9 tests)
├── AssignmentTest.java            - Tests for Assignment entity (6 tests)
├── ScoreTest.java                 - Tests for scoring and grading logic (9 tests)
├── ObjectifyIntegrationTest.java  - Datastore integration tests (8 tests) ⚠️
├── QuizServletTest.java           - Servlet request/response tests (11 tests) ⚠️
├── LTIIntegrationTest.java        - LTI OAuth2 JWT validation tests (13 tests) ⚠️
├── AIValidationTest.java          - Gemini AI validation tests (13 tests) ⚠️
└── TestHelper.java                - Utility class for creating test data
```
⚠️ = Disabled by default, requires additional setup

## Running Tests

### Run all tests:
```bash
mvn test
```

### Run specific test class:
```bash
mvn test -Dtest=QuestionTest
```

### Run tests with coverage (if configured):
```bash
mvn test jacoco:report
```

### Run tests in your IDE:
- **IntelliJ IDEA**: Right-click on test class → Run 'TestClassName'
- **Eclipse**: Right-click on test class → Run As → JUnit Test
- **VS Code**: Click "Run Test" above test method

## Test Categories

### Unit Tests
Test individual classes and methods in isolation without external dependencies:
- `QuestionTest` - Qu
Tests that require database or external service interaction (disabled by default):

- `ObjectifyIntegrationTest` - Datastore CRUD operations, queries, batch operations
  - Requires: `appengine-api-stubs` dependency and `LocalServiceTestHelper`
  
- `QuizServletTest` - Servlet request/response handling, user permissions
  - Requires: `mockito-core` dependency for mocking HttpServletRequest/Response
  
- `LTIIntegrationTest` - LTI 1.3 OAuth2 authentication, JWT validation, role parsing
  - Requires: Valid HMAC secret configuration (some tests)
  
- `AIValidationTest` - Gemini AI question validation, ambiguity detection
  - Requires: Google Cloud Vertex AI credentials and may incur API cost
### Integration Tests (TODO)
Tests that require database or external service interaction:
- LTI launch flow with JWT validation
- Objectify datastore operations
- Score reporting to LMS platforms

## Test Helper Utilities

The `TestHelper` class provides factory methods for creating test data:

```java
// Create test users
User instructor = TestHelper.createTestInstructor();
User learner = TestHelper.createTestLearner();
User anonymous = TestHelper.createAnonymousUser();

// Create test questions
Question mcq = TestHelper.createMultipleChoiceQuestion(1L, "b");
Question numeric = TestHelper.createNumericQuestion(1L, "3.14");

// Create test assignments
Assignment quiz = TestHelper.createTestAssignment("Quiz", "Chapter 1");
```

## Writing New Tests

### Test Naming Convention
- Use descriptive test method names: `testMultipleChoiceCorrectAnswer()`
- Use `@DisplayName` for human-readable descriptions
- Group related tests in the same test class

### Test Structure (AAA Pattern)
```java
@Test
@DisplayName("Description of what is being tested")
void testMethodName() {
    // Arrange - Set up test data
    Question q = new Question(Question.MULTIPLE_CHOICE);
    q.correctAnswer = "b";
    
    // Act - Execute the behavior being tested
    boolean result = q.isCorrect("b");
    
    // Assert - Verify the outcome
    assertTrue(result);
}
```

### JUnit 5 Assertions
Common assertions used in tests:
- `assertEquals(expected, actual)` - Check equality
- `assertTrue(condition)` - Check boolean condition
- `assertFalse(condition)` - Check boolean is false
- `assertNotNull(object)` - Check object is not null
- `assertThrows(Exception.class, () -> {...})` - Check exception is thrown
Areas with test coverage:
- ✅ Question entity business logic
- ✅ User authentication and roles
- ✅ Assignment configuration
- ✅ Score calculations
- ✅ Servlet request/response handling (QuizServletTest)
- ✅ LTI OAuth2 JWT validation (LTIIntegrationTest)
- ✅ Datastore CRUD operations (ObjectifyIntegrationTest)
- ✅ AI validation with Gemini (AIValidationTest)

TODO - Additional areas:
- ⏳ Score passback to LMS (AGS endpoints)
- ⏳ Email sending functionality (SendGrid integration)
- ⏳ HWTransaction and other transaction types
- ⏳ Admin servlet authorization
- ✅ Score calculations

TODO - Areas needing test coverage:
- ⏳ Servlet request/response handling
- ⏳ LTI OAuth2 JWT validation
- ⏳ Datastore CRUD operations
- ⏳ Score passback to LMS
- ⏳ Email sending functionality
- ⏳ AI validation with Gemini

## Mocking External Dependencies

For tests that require external services, use mocking:

```java
GitHub Actions workflow is configured at `.github/workflows/maven-test.yml`:
- ✅ Runs on push to master/main/develop branches
- ✅ Runs on pull requests
- ✅ Tests against Java 21
- ✅ Uploads test results as artifacts
- ✅ Reports test results on PRs

### Enabling Advanced Tests in CI

Add dependencies to `pom.xml` to enable disabled tests:

```xml
<!-- For ObjectifyIntegrationTest -->
<dependency>
  <groupId>com.google.appengine</groupId>
  <artifactId>appengine-api-stubs</artifactId>
  <version>1.9.76</version>
  <scope>test</scope>
</dependency>

<!-- For QuizServletTest -->
<dependency>
  <groupId>org.mockito</groupId>
  <artifactId>mockito-core</artifactId>
  <scope>test</scope>
</dependency>
```

For AIValidationTest, add Google Cloud credentials as GitHub Secrets:
1. Go to Settings → Secrets → Actions
2. Add `GOOGLE_APPLICATION_CREDENTIALS_JSON` with service account JSON
3. Update workflow to inject credentials before test ru
    private ObjectifyService ofy;
    
    @Test
    void testDataAccess() {
        when(ofy.load().type(Question.class).id(123L))
            .thenReturn(mockQuestion);
        // ... test code
    }
}
```

## Continuous Integration

Tests run automatically on:
- Every commit (if CI/CD is configured)
- Before deployment to staging/production
- On pull request creation

## Test Data Cleanup

- Unit tests should not persist data
- Use in-memory objects where possible
- If writing to database (integration tests), clean up in `@AfterEach` methods

## Debugging Failed Tests

1. Check the test output for assertion failures
2. Use IDE debugger to step through test
3. Add `System.out.println()` or logger statements
4. Verify test data setup in `@BeforeEach` method
5. Check for null pointer exceptions in entity fields

## Resources

- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [Spring Boot Testing](https://spring.io/guides/gs/testing-web/)
- [Mockito Documentation](https://site.mockito.org/)
- [Objectify Testing](https://github.com/objectify/objectify/wiki/Testing)
