FROM maven:3.9.12-eclipse-temurin-17 AS build
WORKDIR /build
COPY servicios/vuelos/pom.xml ./pom.xml
COPY servicios/vuelos/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/target/servicio-vuelos-1.0.0.jar app.jar
COPY servicios/vuelos/docker-entrypoint.sh /app/docker-entrypoint.sh
EXPOSE 8081
ENTRYPOINT ["sh", "/app/docker-entrypoint.sh"]
