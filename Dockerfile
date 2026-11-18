# Compile the backend with the project's Maven Wrapper and Java 21.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY --chmod=755 mvnw ./
COPY pom.xml ./
COPY src/ src/
# Integration tests run in CI with their own MySQL, Redis and Kafka services.
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package

# Package the executable JAR with the Java runtime used by the container.
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
COPY --from=build /workspace/target/high-concurrency-lab-0.0.1-SNAPSHOT.jar /app/app.jar
# Use an unprivileged numeric user and group to run the application.
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
