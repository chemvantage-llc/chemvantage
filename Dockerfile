# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src

RUN mvn -q -DskipTests package \
  && JAR_PATH="$(ls target/*.jar | grep -v '\\.jar\\.original$' | head -n 1)" \
  && test -n "$JAR_PATH" \
  && cp "$JAR_PATH" /workspace/app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"
COPY --from=build /workspace/app.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --server.port=${PORT:-8080}"]
