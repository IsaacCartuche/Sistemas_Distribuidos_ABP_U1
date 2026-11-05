FROM maven:3.9.5-eclipse-temurin-17 AS build
WORKDIR /app
COPY servicios/usuarios/pom.xml .
COPY servicios/usuarios/src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]