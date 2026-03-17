# Upgrade Plan: ChemVantage (20260317185226)

- **Generated**: 2026-03-17 18:52:26
- **HEAD Branch**: master
- **HEAD Commit ID**: 4d0fda4d026059c16f6c43e50eefbc371edc076d

## Available Tools

**JDKs**
- JDK 21.0.9: /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home (target, used by all steps)

**Build Tools**
- Maven 3.9.9: /Users/wight/apache-maven-3.9.9 (compatible with Java 21)
- Maven Wrapper: Not present in project

## Guidelines

- Follow ChemVantage coding guidelines for Spring Boot and Objectify
- Ensure Jakarta Servlet compatibility (not javax)
- Maintain Google Cloud Platform App Engine deployment compatibility
- Preserve LTI 1.3 integration functionality

> Note: You can add any specific guidelines or constraints for the upgrade process here if needed, bullet points are preferred.

## Options

- Working branch: appmod/java-upgrade-<SESSION_ID> <!-- user specified, NEVER remove it -->
- Run tests before and after the upgrade: true <!-- user specified, NEVER remove it -->

## Upgrade Goals

- Verify and validate Java 21 LTS runtime (project configuration already targets Java 21)

### Technology Stack

| Technology/Dependency | Current | Min Compatible | Why Incompatible |
| --------------------- | ------- | -------------- | ---------------- |
| Java | 21 (configured) | 21 | Already configured, need runtime verification |
| Spring Boot | 3.5.8 | 3.0.0 | - |
| Spring Framework | (via Spring Boot) | 6.0+ | - |
| Objectify | 6.1.3 | 6.0+ | - |
| Maven | 3.9.9 | 3.9.0 | - |
| Google Cloud Libraries BOM | 26.56.0 | 26.0+ | - |
| Auth0 Java JWT | 4.5.0 | 4.0+ | - |
| Auth0 JWKS RSA | 0.23.0 | 0.20+ | - |
| Google Cloud Tasks | (via BOM) | Latest | - |
| Google Cloud Vertex AI | (via BOM) | Latest | - |
| OpenPDF HTML | 3.0.0 | 2.3+ | - |
| PayPal Checkout SDK | 2.0.0 | 2.0+ | - |
| App Engine SDK | 1.9.76 | 1.9.x | Legacy but functional, monitor for deprecation |
| App Engine Maven Plugin | 2.8.6 | 2.8+ | - |

### Derived Upgrades

**Analysis**: Project is already configured for Java 21:
- pom.xml declares maven.compiler.source=21 and maven.compiler.target=21
- Spring Boot 3.5.8 fully supports Java 21
- App Engine app.yaml already specifies runtime: java21
- All major dependencies are Java 21 compatible

**Required Actions**: No dependency version upgrades needed. Main tasks are:
- Verify compilation with Java 21 JDK
- Validate all tests pass with Java 21 runtime
- Ensure build toolchain uses Java 21

## Upgrade Steps

- **Step 1: Setup Environment**
  - **Rationale**: Verify Java 21 JDK is available and properly configured. No installation needed as JDK 21.0.9 is already present.
  - **Changes to Make**:
    - [ ] Verify JDK 21.0.9 at /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
    - [ ] Confirm Maven 3.9.9 compatibility with Java 21
    - [ ] Document environment setup
  - **Verification**:
    - Command: `java -version` with JAVA_HOME set to Java 21
    - Expected: Output shows Java version 21.0.9

---

- **Step 2: Setup Baseline**
  - **Rationale**: Establish pre-upgrade compile and test results with Java 21 to measure success.
  - **Changes to Make**:
    - [ ] Set JAVA_HOME to Java 21
    - [ ] Run baseline compilation (main + test sources)
    - [ ] Run baseline test suite
    - [ ] Document compilation status and test pass rate
  - **Verification**:
    - Command: `mvn clean test-compile && mvn clean test`
    - JDK: /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
    - Expected: Document SUCCESS/FAILURE for compilation and test pass rate (forms acceptance criteria)

---

- **Step 3: Final Validation**
  - **Rationale**: Verify project meets all Java 21 upgrade success criteria: clean compilation and 100% test pass rate.
  - **Changes to Make**:
    - [ ] Verify pom.xml maven.compiler.source and target are set to 21
    - [ ] Verify app.yaml runtime is set to java21
    - [ ] Run complete clean build with Java 21
    - [ ] Fix any compilation errors if present
    - [ ] Run full test suite and fix ALL test failures until 100% pass rate achieved
    - [ ] Verify Spring Boot application starts successfully
    - [ ] Document final state
  - **Verification**:
    - Command: `mvn clean test`
    - JDK: /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
    - Expected: Compilation SUCCESS + 100% tests pass

## Key Challenges

- **Configuration vs Runtime Mismatch**
  - **Challenge**: Project pom.xml already targets Java 21, but system default is Java 23. Need to ensure consistent use of Java 21 runtime during build/test.
  - **Strategy**: Explicitly set JAVA_HOME to Java 21 path for all Maven operations during upgrade validation.

- **Legacy App Engine SDK**
  - **Challenge**: Using App Engine SDK 1.9.76 which is older, may have limited Java 21 testing/documentation.
  - **Strategy**: Monitor for any runtime issues during testing. App Engine runtime itself targets java21 in app.yaml, so deployment should work correctly.

- **Test Suite Baseline**
  - **Challenge**: Unknown current test pass rate - need to establish baseline before claiming success.
  - **Strategy**: Step 2 will run full test suite with Java 21 to document baseline. Final validation must meet or exceed this baseline.
