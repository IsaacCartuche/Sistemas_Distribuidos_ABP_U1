FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY servicios/notificaciones/pom.xml ./pom.xml
COPY servicios/notificaciones/src ./src
RUN mvn clean package -Dmaven.test.skip=true -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]