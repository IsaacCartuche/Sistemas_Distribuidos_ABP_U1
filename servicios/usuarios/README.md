# servicio-usuarios
Registro, autenticación (JWT), perfiles y roles

## Tecnología
- Java 17 + Spring Boot 3.2
- Spring Data JPA + PostgreSQL
- Spring AMQP + RabbitMQ

## Puerto
Definido en `application.yml` y variable de entorno.

## Variables de entorno
- `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
- `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASS`
- `JAVA_OPTS`

## Construir y ejecutar localmente
```bash
./mvnw clean package
docker build -t servicio-usuarios .
docker run -p 8080:8080 --env-file .env servicio-usuarios
```
