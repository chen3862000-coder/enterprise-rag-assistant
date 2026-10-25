FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/pom.xml backend/mvnw backend/mvnw.cmd ./backend/
COPY backend/.mvn ./backend/.mvn
RUN chmod +x backend/mvnw && cd backend && ./mvnw -B -DskipTests dependency:go-offline
COPY backend/src ./backend/src
COPY evals ./evals
RUN cd backend && ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/backend/target/enterprise-rag-assistant-0.1.0-SNAPSHOT.jar /app/app.jar
COPY synthetic-docs /app/synthetic-docs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
