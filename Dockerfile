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

# Build the project and run tests
RUN mvn clean package -B

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy only the built JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

# Run the application
ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
