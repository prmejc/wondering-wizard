# Stage 1: Build and test
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Install Maven
RUN apt-get update && \
    apt-get install -y maven && \
    rm -rf /var/lib/apt/lists/*

# Copy Maven configuration
COPY pom.xml .

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source files
COPY src ./src

# Build the project, run tests, and copy dependencies
RUN mvn clean package -B && \
    mvn dependency:copy-dependencies -DoutputDirectory=target/libs -B

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the built JAR and its dependencies from the build stage
COPY --from=build /app/target/event-processing-engine-1.0-SNAPSHOT.jar app.jar
COPY --from=build /app/target/libs/ libs/

# Run the application with JPMS module reads for unnamed modules (Kafka, Avro, etc.)
ENTRYPOINT ["java", "--add-reads", "com.wonderingwizard=ALL-UNNAMED", "--add-reads", "io.opentelemetry.exporter.prometheus=ALL-UNNAMED", "--add-reads", "io.opentelemetry.sdk=ALL-UNNAMED", "-cp", "app.jar:libs/*", "com.wonderingwizard.Main"]
