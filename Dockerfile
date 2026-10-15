# Estágio de construção
FROM maven:3.8.4-openjdk-17-slim AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src src
RUN mvn clean package -DskipTests

# Estágio de execução (não root: o deployment exige runAsNonRoot)
FROM eclipse-temurin:17-jre-alpine
COPY --from=builder /app/target/*.jar /app.jar
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
