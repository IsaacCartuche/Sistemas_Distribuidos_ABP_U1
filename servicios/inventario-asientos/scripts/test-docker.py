"""Build desde contexto raíz y smoke test exclusivo de recursos Docker nuevos."""
import base64
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

SERVICE = Path(__file__).resolve().parents[1]
ROOT = SERVICE.parents[1]
LABEL = "inventario-docker-prueba"
USER = "inventario_test"
PASSWORD = "inventario_test_aislado"


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


def http(base, method, path, body=None, auth=False):
    headers = {"Content-Type": "application/json"}
    if auth:
        credential = base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
        headers["Authorization"] = "Basic " + credential
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    with urllib.request.urlopen(request, timeout=5) as response:
        content = response.read()
        return response.status, json.loads(content) if content else None


def wait_for(check, description):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(1)
    raise AssertionError("Timeout: " + description)


def port(container, internal):
    return docker("port", container, f"{internal}/tcp").rsplit(":", 1)[1]


def sql(postgres, query):
    return docker("exec", postgres, "psql", "-U", USER, "-d", "db_inventario",
                  "-At", "-v", "ON_ERROR_STOP=1", "-c", query)


def main():
    suffix = uuid.uuid4().hex[:12]
    image = f"inventario-verificacion-{suffix}:temporal"
    containers = []
    network = None
    try:
        # Contexto raíz real; target y los JARs locales quedan fuera mediante Dockerfile.dockerignore.
        subprocess.run(["docker", "build", "--progress=plain", "--file", str(SERVICE / "Dockerfile"),
                        "--tag", image, str(ROOT)], check=True)
        network = docker("network", "create", "--label", f"proyecto={LABEL}", f"{LABEL}-{suffix}")

        def start(*args):
            container = docker("run", "--detach", "--rm", "--label", f"proyecto={LABEL}",
                               "--network", network, *args)
            containers.append(container)
            return container

        postgres = start("--network-alias", "inventario-db-prueba", "--env", "POSTGRES_DB=db_inventario",
                         "--env", f"POSTGRES_USER={USER}", "--env", f"POSTGRES_PASSWORD={PASSWORD}",
                         "--tmpfs", "/var/lib/postgresql/data", "--mount",
                         f"type=bind,source={SERVICE / 'sql/init.sql'},target=/docker-entrypoint-initdb.d/init.sql,readonly",
                         "postgres:15-alpine")
        rabbit = start("--network-alias", "inventario-rabbit-prueba",
                       "--env", f"RABBITMQ_DEFAULT_USER={USER}", "--env", f"RABBITMQ_DEFAULT_PASS={PASSWORD}",
                       "--env", "RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2",
                       "--tmpfs", "/var/lib/rabbitmq:uid=999,gid=999,mode=1777",
                       "--publish", "127.0.0.1::15672", "rabbitmq:3-management")
        management = "http://127.0.0.1:" + port(rabbit, 15672)
        wait_for(lambda: http(management, "GET", "/api/overview", auth=True)[0] == 200, "RabbitMQ")
        wait_for(lambda: subprocess.run(
            ["docker", "exec", postgres, "pg_isready", "-h", "127.0.0.1", "-U", USER, "-d", "db_inventario"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0, "PostgreSQL")
        assert sql(postgres, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                   "AND table_name IN ('asientos','retenciones','inventario_inbox','inventario_solicitudes','inventario_outbox')") == "5"
        assert sql(postgres, "SELECT character_maximum_length FROM information_schema.columns "
                   "WHERE table_name='inventario_inbox' AND column_name='tipo'") == "50"
        assert sql(postgres, "SELECT count(*) FROM information_schema.columns "
                   "WHERE table_name='inventario_outbox' AND column_name='clave_negocio'") == "1"

        def broker(method, path, body):
            return http(management, method, "/api/" + path, body, auth=True)[1]

        for exchange in ("vuelos.events", "vuelos.dlx"):
            broker("PUT", f"exchanges/%2F/{exchange}", {"type": "topic", "durable": True, "arguments": {}})
        for queue, arguments in (
            ("inventario.entrada", {"x-max-priority": 5, "x-dead-letter-exchange": "vuelos.dlx",
                                    "x-dead-letter-routing-key": "inventario.dlq"}),
            ("inventario.resultados.pruebas", {"x-max-priority": 10}),
            ("inventario.dlq", {}),
        ):
            broker("PUT", f"queues/%2F/{queue}", {"durable": True, "arguments": arguments})
        for exchange, queue, key in (
            ("vuelos.events", "inventario.entrada", "reserva.creada"),
            ("vuelos.events", "inventario.resultados.pruebas", "asiento.bloqueado"),
            ("vuelos.dlx", "inventario.dlq", "inventario.dlq"),
        ):
            broker("POST", f"bindings/%2F/e/{exchange}/q/{queue}", {"routing_key": key, "arguments": {}})

        app = start("--publish", "127.0.0.1::8085", "--env", "POSTGRES_HOST=inventario-db-prueba",
                    "--env", "POSTGRES_PORT=5432", "--env", f"POSTGRES_USER={USER}",
                    "--env", f"POSTGRES_PASSWORD={PASSWORD}", "--env", "RABBITMQ_HOST=inventario-rabbit-prueba",
                    "--env", "RABBITMQ_PORT=5672", "--env", f"RABBITMQ_USER={USER}",
                    "--env", f"RABBITMQ_PASS={PASSWORD}", "--env", "INVENTARIO_CONSUMIDOR_HABILITADO=true",
                    "--env", "JAVA_OPTS=-Xms128m -Xmx256m -Dverificacion.inventario=true",
                    "--env", "JAVA_TOOL_OPTIONS=-XX:+PrintCommandLineFlags -Dverificacion.inventario.tool=true", image)
        base = "http://127.0.0.1:" + port(app, 8085)
        wait_for(lambda: http(base, "GET", "/api/inventario/vuelos/1/asientos")[0] == 200, "HTTP 8085")
        assert http(base, "GET", "/api/v1/inventario/vuelos/1/asientos")[1] == []
        command = docker("top", app, "-eo", "pid,args")
        assert "java -Xms128m -Xmx256m -Dverificacion.inventario=true -jar /app/app.jar" in command
        assert docker("exec", app, "readlink", "/proc/1/exe").endswith("/java")
        logs = docker("logs", app)
        assert "-XX:MaxHeapSize=268435456" in logs  # PrintCommandLineFlags desde JAVA_TOOL_OPTIONS.
        assert docker("image", "inspect", image, "--format", '{{json .Config.ExposedPorts}}') == '{"8085/tcp":{}}'

        status, seats = http(base, "PUT", "/api/inventario/vuelos/1/asientos", {"numeros": ["1A"]})
        assert status == 200 and len(seats) == 1
        seat_id = seats[0]["id"]
        correlation, token = str(uuid.uuid4()), str(uuid.uuid4())
        event = {"eventId": str(uuid.uuid4()), "correlationId": correlation,
                 "timestamp": "2026-11-10T18:00:00Z", "tipo": "reserva.creada",
                 "payload": {"reservaId": 123, "vueloId": 1, "asiento": "1A", "token": token}}
        assert broker("POST", "exchanges/%2F/vuelos.events/publish", {
            "routing_key": "reserva.creada", "payload": json.dumps(event), "payload_encoding": "string",
            "properties": {"content_type": "application/json", "delivery_mode": 2}})["routed"]
        wait_for(lambda: sql(postgres, "SELECT count(*) FROM inventario_outbox WHERE estado='ENVIADO'") == "1",
                 "Inbox/bloqueo/Outbox RabbitMQ")
        assert sql(postgres, "SELECT estado FROM asientos") == "BLOQUEADO"
        assert sql(postgres, "SELECT resultado FROM inventario_inbox") == "ASIENTO_BLOQUEADO"
        messages = broker("POST", "queues/%2F/inventario.resultados.pruebas/get",
                          {"count": 1, "ackmode": "ack_requeue_false", "encoding": "auto"})
        assert len(messages) == 1
        result = json.loads(messages[0]["payload"])
        assert result["tipo"] == "asiento.bloqueado" and result["correlationId"] == correlation
        assert result["payload"]["token"] == token and result["payload"]["asientoId"] == seat_id
        assert http(base, "GET", f"/api/v1/inventario/asientos/{seat_id}")[1]["estado"] == "BLOQUEADO"
        print("OK: build raíz, JRE 17, puerto 8085, esquema completo, ambas rutas, JAVA_OPTS/JAVA_TOOL_OPTIONS, "
              "PID 1 Java, Inbox y Outbox con PostgreSQL/RabbitMQ aislados.")
        print("Imagen de verificación:", image)
    except Exception:
        for container in containers:
            subprocess.run(["docker", "logs", "--tail", "80", container], check=False)
        raise
    finally:
        # Solo IDs obtenidos de docker run en esta ejecución; nunca nombres de servicios existentes.
        for container in reversed(containers):
            subprocess.run(["docker", "stop", container], check=False, stdout=subprocess.DEVNULL)
        if network:
            subprocess.run(["docker", "network", "rm", network], check=False, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
