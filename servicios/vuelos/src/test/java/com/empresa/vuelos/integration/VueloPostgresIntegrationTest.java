package com.empresa.vuelos.integration;

import com.empresa.vuelos.api.dto.VueloRequest;
import com.empresa.vuelos.api.dto.VueloResponse;
import com.empresa.vuelos.domain.EstadoVuelo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Optativa: ejecutar solamente contra un PostgreSQL de prueba aislado, inicializado con sql/init.sql. */
@EnabledIfEnvironmentVariable(named = "VUELOS_POSTGRES_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VueloPostgresIntegrationTest {

    private static final String URL = "/api/v1/vuelos";

    @DynamicPropertySource
    static void configurarPostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> variable("VUELOS_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> variable("VUELOS_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> variable("VUELOS_TEST_DB_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void crearYConsultarPersisteTiposEIdentidadEnPostgres() {
        VueloRequest request = request(" Quito ", ciudad("Loja"), "125.50");
        ResponseEntity<VueloResponse> created = http.postForEntity(URL, request, VueloResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        VueloResponse vuelo = created.getBody();
        assertThat(vuelo).isNotNull();
        assertThat(vuelo.id()).isPositive();
        assertThat(created.getHeaders().getLocation()).hasPath(URL + "/" + vuelo.id());
        assertThat(vuelo.origen()).isEqualTo("Quito");
        assertThat(vuelo.fechaCreacion()).isNotNull();

        ResponseEntity<VueloResponse> queried = http.getForEntity(URL + "/" + vuelo.id(), VueloResponse.class);
        assertThat(queried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queried.getBody()).isNotNull();
        assertThat(queried.getBody().precio()).isEqualByComparingTo("125.50");
        assertThat(queried.getBody().fechaSalida()).isEqualTo(request.fechaSalida());
        assertThat(queried.getBody().horaSalida()).isEqualTo(request.horaSalida());
        assertThat(queried.getBody().estado()).isEqualTo(EstadoVuelo.ACTIVO);
        assertThat(jdbc.queryForObject("select precio from vuelos where id = ?", BigDecimal.class, vuelo.id()))
                .isEqualByComparingTo("125.50");
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("db_vuelos");
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "false,true,false", "false,false,true",
            "true,true,false", "true,false,true", "false,true,true", "true,true,true"})
    void listarYBuscarConTodasLasCombinacionesDeFiltros(boolean origen, boolean destino, boolean fecha) {
        String ciudadOrigen = ciudad("Quito");
        String ciudadDestino = ciudad("Loja");
        VueloResponse vuelo = crear(request(ciudadOrigen, ciudadDestino, "12.34"));
        crear(request(ciudad("OtroOrigen"), ciudad("OtroDestino"), "20.00"));
        crear(new VueloRequest(ciudadOrigen, ciudadDestino, LocalDate.of(2027, 5, 11),
                LocalTime.of(8, 0), new BigDecimal("20.00"), EstadoVuelo.ACTIVO, 100, 80));
        String query = URL + "?";
        if (origen) query += "origen=" + ciudadOrigen.toLowerCase() + "&";
        if (destino) query += "destino=" + ciudadDestino.toUpperCase() + "&";
        if (fecha) query += "fecha=2027-05-10";

        ResponseEntity<VueloResponse[]> response = http.getForEntity(query, VueloResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).extracting(VueloResponse::id).contains(vuelo.id());
        assertThat(response.getBody()).allSatisfy(item -> {
            if (origen) assertThat(item.origen()).isEqualToIgnoringCase(ciudadOrigen);
            if (destino) assertThat(item.destino()).isEqualToIgnoringCase(ciudadDestino);
            if (fecha) assertThat(item.fechaSalida()).isEqualTo(LocalDate.of(2027, 5, 10));
        });
        assertThat(Arrays.asList(response.getBody())).isSortedAccordingTo(
                java.util.Comparator.comparing(VueloResponse::fechaSalida)
                        .thenComparing(VueloResponse::horaSalida).thenComparing(VueloResponse::id));
    }

    @Test
    void actualizarPersisteCambiosYConservaFechaCreacion() {
        VueloResponse original = crear(request(ciudad("Quito"), "Loja", "10.01"));
        VueloRequest cambio = new VueloRequest(ciudad("Cuenca"), "Guayaquil", LocalDate.of(2028, 6, 12),
                LocalTime.of(15, 45), new BigDecimal("99999999.99"), EstadoVuelo.CANCELADO, 120, 0);

        ResponseEntity<VueloResponse> updated = http.exchange(URL + "/" + original.id(), HttpMethod.PUT,
                new HttpEntity<>(cambio), VueloResponse.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        VueloResponse persisted = http.getForObject(URL + "/" + original.id(), VueloResponse.class);
        assertThat(persisted).isNotNull();
        assertThat(persisted.id()).isEqualTo(original.id());
        assertThat(persisted.origen()).isEqualTo(cambio.origen());
        assertThat(persisted.destino()).isEqualTo(cambio.destino());
        assertThat(persisted.precio()).isEqualByComparingTo("99999999.99");
        assertThat(persisted.fechaSalida()).isEqualTo(cambio.fechaSalida());
        assertThat(persisted.horaSalida()).isEqualTo(cambio.horaSalida());
        assertThat(persisted.estado()).isEqualTo(EstadoVuelo.CANCELADO);
        assertThat(persisted.capacidadTotal()).isEqualTo(120);
        assertThat(persisted.capacidadDisponible()).isZero();
        // PostgreSQL TIMESTAMP conserva microsegundos, no los nanosegundos del reloj Java.
        assertThat(persisted.fechaCreacion()).isEqualTo(updated.getBody().fechaCreacion());
        assertThat(persisted.fechaCreacion()).isCloseTo(original.fechaCreacion(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void busquedaSinCoincidenciasDevuelveArrayVacio() {
        ResponseEntity<VueloResponse[]> response = http.getForEntity(
                URL + "?origen=" + ciudad("Ausente") + "&fecha=2099-01-01", VueloResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void inexistenteDevuelve404YActualizarNoInserta() {
        int id = Integer.MAX_VALUE;
        assertThat(http.getForEntity(URL + "/" + id, ProblemDetail.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        Integer antes = jdbc.queryForObject("select count(*) from vuelos", Integer.class);
        ResponseEntity<ProblemDetail> response = http.exchange(URL + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(request("Quito", "Loja", "10.00")), ProblemDetail.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("select count(*) from vuelos", Integer.class)).isEqualTo(antes);
    }

    @Test
    void solicitudesInvalidasNoInsertanFilas() {
        Integer antes = jdbc.queryForObject("select count(*) from vuelos", Integer.class);
        for (VueloRequest invalida : new VueloRequest[]{request("Quito", " quito ", "10.00"),
                request("Quito", "Loja", "0.00"), request("Quito", "Loja", "1.234"),
                new VueloRequest("Quito", "Loja", LocalDate.of(2027, 5, 10), LocalTime.of(9, 30),
                        new BigDecimal("10.00"), EstadoVuelo.ACTIVO, 10, 11)}) {
            ResponseEntity<ProblemDetail> response = http.postForEntity(URL, invalida, ProblemDetail.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
        }
        assertThat(jdbc.queryForObject("select count(*) from vuelos", Integer.class)).isEqualTo(antes);
    }

    private VueloResponse crear(VueloRequest request) {
        ResponseEntity<VueloResponse> response = http.postForEntity(URL, request, VueloResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private VueloRequest request(String origen, String destino, String precio) {
        return new VueloRequest(origen, destino, LocalDate.of(2027, 5, 10), LocalTime.of(9, 30),
                new BigDecimal(precio), EstadoVuelo.ACTIVO, 100, 80);
    }

    private String ciudad(String prefijo) {
        return prefijo + UUID.randomUUID().toString().replace("-", "");
    }

    private static String variable(String nombre) {
        String valor = System.getenv(nombre);
        if (valor == null || valor.isBlank()) throw new IllegalStateException("Falta " + nombre);
        return valor;
    }
}
