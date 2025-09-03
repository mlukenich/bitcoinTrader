# Stage 1: Build the application using a pre-configured Maven image
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy the entire project
COPY . .

# Run the standard package command
RUN mvn clean package -DskipTests

# ---

# Stage 2: Create the final runtime image
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Copy the built JAR from the build stage
# IMPORTANT: Make sure this filename matches the one in your /target directory
COPY --from=build /app/target/trading-bot-0.0.1-SNAPSHOT.jar app.jar

# Expose the port the application runs on
EXPOSE 8080

# The command to run the application
ENTRYPOINT ["java", "-jar", "app.jar"]