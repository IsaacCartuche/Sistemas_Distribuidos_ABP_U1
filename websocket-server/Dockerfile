FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY websocket-server/pom.xml ./pom.xml
COPY websocket-server/src ./src
RUN mvn clean package -Dmaven.test.skip=true -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]