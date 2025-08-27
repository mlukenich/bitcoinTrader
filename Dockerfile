# Stage 1: Build the application using Maven and a full JDK
FROM eclipse-temurin:17-jdk-jammy AS build

# Set the working directory inside the container
WORKDIR /app

# Copy the Maven project files
COPY pom.xml .
COPY src ./src

# Install Maven
RUN apt-get update && apt-get install -y maven

# Now build the project
RUN mvn clean package -DskipTests

# ---

# Stage 2: Create the final, lightweight runtime image
FROM eclipse-temurin:17-jre-jammy

# Set the working directory
WORKDIR /app

# Copy the executable JAR from the 'build' stage
COPY --from=build /app/target/*.jar app.jar

# Expose the port the application runs on
EXPOSE 8080

# The command to run the application
ENTRYPOINT ["java", "-jar", "app.jar"]