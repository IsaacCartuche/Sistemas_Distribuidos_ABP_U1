FROM maven:3.9.5-eclipse-temurin-17 AS build
WORKDIR /app
COPY gateway/pom.xml .
COPY gateway/src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]