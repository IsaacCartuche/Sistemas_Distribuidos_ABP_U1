FROM maven:3.9.12-eclipse-temurin-17 AS build
WORKDIR /build
COPY servicios/inventario-asientos/pom.xml ./pom.xml
COPY servicios/inventario-asientos/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/target/servicio-inventario-asientos-1.0.0.jar app.jar
COPY servicios/inventario-asientos/docker-entrypoint.sh /app/docker-entrypoint.sh
EXPOSE 8085
ENTRYPOINT ["sh", "/app/docker-entrypoint.sh"]
