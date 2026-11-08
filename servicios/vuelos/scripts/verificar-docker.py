"""Smoke test exclusivo de los contenedores temporales creados por test-docker.sh."""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

port, app, postgres = sys.argv[1:]
base = f"http://127.0.0.1:{port}"


def request(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as response:
        return response.status, response.headers, json.load(response)


deadline = time.monotonic() + 60
while True:
    try:
        assert request("GET", "/api/vuelos")[0] == 200
        break
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        if time.monotonic() > deadline:
            subprocess.run(["docker", "logs", app], check=False)
            raise
        time.sleep(1)

body = dict(origen="Quito", destino="Loja", fechaSalida="2027-05-10", horaSalida="09:30:00",
            precio=125.50, estado="ACTIVO", capacidadTotal=100, capacidadDisponible=80)
status, headers, vuelo = request("POST", "/api/vuelos", body)
assert status == 201 and "/api/vuelos/" in headers["Location"]
flight_id = vuelo["id"]
assert request("GET", f"/api/v1/vuelos/{flight_id}")[2]["origen"] == "Quito"
body["precio"] = 130.00
assert request("PUT", f"/api/vuelos/{flight_id}", body)[0] == 200
assert request("PUT", f"/api/v1/vuelos/{flight_id}", body)[0] == 200


def sql(query):
    return subprocess.check_output(["docker", "exec", postgres, "psql", "-U", "vuelos_test", "-d", "db_vuelos",
                                    "-At", "-v", "ON_ERROR_STOP=1", "-c", query], text=True).strip()


assert sql("select version from vuelos") == "1"
assert sql("select count(*) from vuelos_outbox") == "1"
deadline = time.monotonic() + 30
while sql("select intentos from vuelos_outbox") == "0":
    if time.monotonic() > deadline:
        subprocess.run(["docker", "logs", app], check=False)
        raise AssertionError("No se intentó publicar con broker desconectado")
    time.sleep(1)
assert sql("select estado from vuelos_outbox") == "PENDIENTE"
assert request("GET", "/api/vuelos")[0] == 200
command = subprocess.check_output(["docker", "top", app, "-eo", "pid,args"], text=True)
assert "java -Xms128m -Xmx256m -Dverificacion.vuelos=true -jar /app/app.jar" in command
print("Docker: build desde contexto raíz, JAVA_OPTS, puerto 8081, ambas rutas, PostgreSQL, no-op y arranque sin RabbitMQ: OK")
