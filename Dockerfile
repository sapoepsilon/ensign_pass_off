# Use the official maven/Java 17 image to create a build artifact.
FROM maven:3.8.4-openjdk-17 as builder

# Set the working directory in the builder container
WORKDIR /workspace/app

# Copy the pom.xml file and download the dependencies
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy the source code and build the application
COPY src ./src
RUN mvn clean package

# Use OpenJDK 17 for the runtime environment
FROM openjdk:17-jdk-slim

# Set the working directory in the container
WORKDIR /app

# Copy the jar file built in the builder stage into this new stage
COPY --from=builder /workspace/app/target/*.jar app.jar

# Run the application
CMD ["java", "-jar", "app.jar"]
