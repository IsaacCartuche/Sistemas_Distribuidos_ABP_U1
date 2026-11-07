package com.empresa.inventario.integration;

import com.empresa.inventario.api.InventarioDto.*;
import com.empresa.inventario.application.*;
import com.empresa.inventario.domain.*;
import com.empresa.inventario.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INVENTARIO_POSTGRES_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"inventario.expiracion-habilitada=false", "inventario.ttl-segundos=60"})
@Import(InventarioPostgresTest.RelojConfig.class)
class InventarioPostgresTest {
    private static final String BASE = "/api/v1/inventario";
    private static final AtomicInteger VUELOS = new AtomicInteger(10000);
    @Autowired TestRestTemplate http;
    @Autowired InventarioService service;
    @Autowired AsientoRepository asientos;
    @Autowired RetencionRepository retenciones;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;
    @Autowired RelojPrueba reloj;
    private int vuelo;

    @DynamicPropertySource
    static void base(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requerida("INVENTARIO_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> requerida("INVENTARIO_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> requerida("INVENTARIO_TEST_DB_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.sql.init.mode", () -> "never");
    }
    static String requerida(String nombre) {
        String valor = System.getenv(nombre);
        if (valor == null || valor.isBlank()) throw new IllegalStateException("Falta " + nombre);
        return valor;
    }
    @BeforeEach
    void preparar() {
        vuelo = VUELOS.incrementAndGet();
        reloj.instante.set(Instant.parse("2027-01-01T12:00:00Z"));
    }

    @Test
    void inicializacionIdempotenteNoModificaAsientosExistentes() {
        List<AsientoResponse> primeros = service.inicializar(vuelo, new InicializarRequest(List.of("1a", "1A", "2B")));
        UUID token = UUID.randomUUID();
        service.bloquear(primeros.get(0).id(), token);
        var repetidos = service.inicializar(vuelo, new InicializarRequest(List.of("2b", "1A")));
        assertThat(repetidos).hasSize(2);
        assertThat(repetidos.get(0).id()).isEqualTo(primeros.get(0).id());
        assertThat(repetidos.get(0).estado()).isEqualTo(EstadoAsiento.BLOQUEADO);
        assertThat(repetidos.get(0).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("db_inventario");
    }

    @Test
    void inicializacionesSimultaneasNoDuplican() throws Exception {
        List<Integer> statuses = paralelos(4, i -> http.exchange(BASE + "/vuelos/" + vuelo + "/asientos",
                HttpMethod.PUT, new HttpEntity<>(new InicializarRequest(i % 2 == 0
                        ? List.of("1A", "2B") : List.of("2b", "1a"))), String.class).getStatusCode().value());
        assertThat(statuses).containsOnly(200);
        assertThat(service.listar(vuelo, false)).hasSize(2).allSatisfy(a -> assertThat(a.version()).isZero());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4, 8})
    void exactamenteUnBloqueoGanaEntreSolicitudesHttpSimultaneas(int competidores) throws Exception {
        int id = nuevo();
        List<Integer> statuses = paralelos(competidores, i -> bloquearHttp(id, UUID.randomUUID()).getStatusCode().value());
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(competidores - 1);
        assertThat(service.consultar(id).estado()).isEqualTo(EstadoAsiento.BLOQUEADO);
        assertThat(service.consultar(id).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from retenciones where asiento_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void versionImpideDosCommitsTrasLeerLaMismaVersion() throws Exception {
        int id = nuevo();
        CyclicBarrier leidos = new CyclicBarrier(2);
        List<Boolean> ganadores = paralelos(2, i -> {
            try {
                new TransactionTemplate(tm).executeWithoutResult(status -> {
                    Asiento a = asientos.findById(id).orElseThrow();
                    esperar(leidos);
                    UUID token = UUID.randomUUID();
                    Retencion r = new Retencion(token, id, service.ahora().plusSeconds(60));
                    retenciones.save(r);
                    a.bloquear(token, r.getExpiraEn());
                    asientos.flush();
                });
                return true;
            } catch (OptimisticLockingFailureException exception) { return false; }
        });
        assertThat(ganadores).containsExactlyInAnyOrder(true, false);
        assertThat(service.consultar(id).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from retenciones where asiento_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void duplicadosConcurrentesMismoTokenSonReintentablesSinRenovar() throws Exception {
        int id = nuevo();
        UUID token = UUID.randomUUID();
        var statuses = paralelos(4, i -> bloquearHttp(id, token).getStatusCode().value());
        assertThat(statuses).contains(200).allMatch(s -> s == 200 || s == 409);
        var primera = service.bloquear(id, token);
        reloj.avanzar(30);
        assertThat(service.bloquear(id, token)).isEqualTo(primera);
        assertThat(service.consultar(id).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from retenciones where token = ?", Integer.class, token)).isEqualTo(1);
    }

    @Test
    void confirmacionHttpEsIdempotenteYNoPermiteLiberarReserva() {
        int id = nuevo();
        UUID token = UUID.randomUUID();
        assertThat(bloquearHttp(id, token).getStatusCode()).isEqualTo(HttpStatus.OK);
        String url = bloqueoUrl(id, token);
        for (int i = 0; i < 2; i++) {
            var response = http.postForEntity(url + "/confirmacion", null, RetencionResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().estado()).isEqualTo(EstadoRetencion.CONFIRMADA);
        }
        reloj.avanzar(100);
        service.expirarUno(id);
        assertThat(service.consultar(id).estado()).isEqualTo(EstadoAsiento.RESERVADO);
        assertThat(service.consultar(id).version()).isEqualTo(2);
        assertThat(http.exchange(url, HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(bloquearHttp(id, UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(service.listar(vuelo, true)).isEmpty();
    }

    @Test
    void expiracionEnLimiteNoConfirmaYDisponibilidadEsRealSinScheduler() {
        int id = nuevo();
        UUID token = UUID.randomUUID();
        service.bloquear(id, token);
        reloj.avanzar(60);
        assertThat(service.listar(vuelo, true)).extracting(AsientoResponse::id).containsExactly(id);
        assertThat(http.postForEntity(bloqueoUrl(id, token) + "/confirmacion", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(bloquearHttp(id, token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        new ExpiracionProgramada(asientos, service).ejecutar();
        assertThat(service.consultar(id).estado()).isEqualTo(EstadoAsiento.DISPONIBLE);
        assertThat(retenciones.findById(token).orElseThrow().getEstado()).isEqualTo(EstadoRetencion.EXPIRADA);
    }

    @Test
    void tokenCanceladoNoReabreYReintentoNoLiberaBloqueoNuevo() {
        int id = nuevo();
        UUID viejo = UUID.randomUUID();
        service.bloquear(id, viejo);
        assertThat(http.exchange(bloqueoUrl(id, viejo), HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bloquearHttp(id, viejo).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        UUID nuevo = UUID.randomUUID();
        service.bloquear(id, nuevo);
        int version = service.consultar(id).version();
        service.liberar(id, viejo);
        assertThat(service.consultar(id).version()).isEqualTo(version);
        assertThat(asientos.findById(id).orElseThrow().getBloqueoToken()).isEqualTo(nuevo);
        assertThat(http.postForEntity(bloqueoUrl(id, viejo) + "/confirmacion", null, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void tokenExpiradoNoLiberaNuevaRetencionNiSeReutiliza() {
        int id = nuevo();
        UUID viejo = UUID.randomUUID();
        service.bloquear(id, viejo);
        reloj.avanzar(60);
        UUID nuevo = UUID.randomUUID();
        service.bloquear(id, nuevo); // Recuperación perezosa, sin scheduler.
        service.liberar(id, viejo);
        service.expirarUno(id); // Candidato obtenido antes del rebloqueo.
        assertThat(asientos.findById(id).orElseThrow().getBloqueoToken()).isEqualTo(nuevo);
        assertThat(retenciones.findById(viejo).orElseThrow().getEstado()).isEqualTo(EstadoRetencion.EXPIRADA);
        assertThat(bloquearHttp(id, viejo).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void tokenDeOtroAsientoYTokenDesconocidoNoAutorizanCambios() {
        var lista = service.inicializar(vuelo, new InicializarRequest(List.of("1A", "1B")));
        int id = lista.get(0).id();
        int otro = lista.get(1).id();
        UUID token = UUID.randomUUID();
        service.bloquear(id, token);
        assertThat(bloquearHttp(otro, token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity(bloqueoUrl(otro, token) + "/confirmacion", null, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange(bloqueoUrl(otro, token), HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange(bloqueoUrl(id, UUID.randomUUID()), HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(service.consultar(id).version()).isEqualTo(1);
        assertThat(service.consultar(otro).disponible()).isTrue();
    }

    @Test
    void confirmarYCancelarConcurrentementeNoDejanEstadoInconsistente() throws Exception {
        int id = nuevo();
        UUID token = UUID.randomUUID();
        service.bloquear(id, token);
        var statuses = paralelos(2, i -> i == 0
                ? http.postForEntity(bloqueoUrl(id, token) + "/confirmacion", null, String.class).getStatusCode().value()
                : http.exchange(bloqueoUrl(id, token), HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getStatusCode().value());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        EstadoRetencion estado = retenciones.findById(token).orElseThrow().getEstado();
        assertThat(service.consultar(id).estado()).isEqualTo(estado == EstadoRetencion.CONFIRMADA
                ? EstadoAsiento.RESERVADO : EstadoAsiento.DISPONIBLE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiradorConSnapshotAntiguoNoLiberaNuevoBloqueoOReserva(boolean confirmarNuevo) throws Exception {
        int id = nuevo();
        UUID viejo = UUID.randomUUID();
        service.bloquear(id, viejo);
        reloj.avanzar(60);
        UUID nuevo = UUID.randomUUID();
        boolean conflicto = snapshotObsoleto(id, viejo, false, () -> {
            service.bloquear(id, nuevo);
            if (confirmarNuevo) service.confirmar(id, nuevo);
        });
        assertThat(conflicto).isTrue();
        assertThat(asientos.findById(id).orElseThrow().getBloqueoToken()).isEqualTo(nuevo);
        assertThat(service.consultar(id).estado()).isEqualTo(confirmarNuevo ? EstadoAsiento.RESERVADO : EstadoAsiento.BLOQUEADO);
    }

    @Test
    void confirmacionConSnapshotAntiguoNoResucitaRetencionExpirada() throws Exception {
        int id = nuevo();
        UUID token = UUID.randomUUID();
        service.bloquear(id, token);
        assertThat(snapshotObsoleto(id, token, true, () -> {
            reloj.avanzar(60);
            service.expirarUno(id);
        })).isTrue();
        assertThat(service.consultar(id).estado()).isEqualTo(EstadoAsiento.DISPONIBLE);
        assertThat(retenciones.findById(token).orElseThrow().getEstado()).isEqualTo(EstadoRetencion.EXPIRADA);
    }

    @Test
    void validacionHttpYAsientoInexistente() {
        var invalid = http.exchange(BASE + "/vuelos/" + vuelo + "/asientos", HttpMethod.PUT,
                new HttpEntity<>(new InicializarRequest(List.of(""))), String.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(service.listar(vuelo, false)).isEmpty();
        assertThat(bloquearHttp(Integer.MAX_VALUE, UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private boolean snapshotObsoleto(int id, UUID token, boolean confirmar, Runnable cambio) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch leido = new CountDownLatch(1);
        CountDownLatch continuar = new CountDownLatch(1);
        try {
            Future<Boolean> resultado = pool.submit(() -> {
                try {
                    new TransactionTemplate(tm).executeWithoutResult(status -> {
                        Asiento a = asientos.findById(id).orElseThrow();
                        Retencion r = retenciones.findById(token).orElseThrow();
                        assertThat(a.getEstado()).isEqualTo(EstadoAsiento.BLOQUEADO);
                        if (confirmar) assertThat(r.getExpiraEn()).isAfter(service.ahora());
                        else assertThat(a.expirado(service.ahora())).isTrue();
                        leido.countDown();
                        esperar(continuar);
                        if (confirmar) { a.confirmar(); r.terminar(EstadoRetencion.CONFIRMADA); }
                        else { a.liberar(); r.terminar(EstadoRetencion.EXPIRADA); }
                        asientos.flush();
                    });
                    return false;
                } catch (OptimisticLockingFailureException exception) { return true; }
            });
            assertThat(leido.await(10, TimeUnit.SECONDS)).isTrue();
            cambio.run();
            continuar.countDown();
            return resultado.get(15, TimeUnit.SECONDS);
        } finally {
            continuar.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private int nuevo() { return service.inicializar(vuelo, new InicializarRequest(List.of("1A"))).get(0).id(); }
    private String bloqueoUrl(int id, UUID token) { return BASE + "/asientos/" + id + "/bloqueos/" + token; }
    private ResponseEntity<String> bloquearHttp(int id, UUID token) {
        return http.exchange(bloqueoUrl(id, token), HttpMethod.PUT, HttpEntity.EMPTY, String.class);
    }
    private <T> List<T> paralelos(int cantidad, IntFunction<T> accion) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(cantidad);
        CyclicBarrier salida = new CyclicBarrier(cantidad);
        try {
            List<Future<T>> futures = IntStream.range(0, cantidad).mapToObj(i -> pool.submit(() -> {
                esperar(salida);
                return accion.apply(i);
            })).toList();
            List<T> resultados = new ArrayList<>();
            for (Future<T> future : futures) resultados.add(future.get(20, TimeUnit.SECONDS));
            return resultados;
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    private static void esperar(CyclicBarrier barrera) {
        try { barrera.await(10, TimeUnit.SECONDS); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static void esperar(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Tiempo de espera agotado"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
    @TestConfiguration
    static class RelojConfig {
        @Bean @Primary RelojPrueba relojPrueba() { return new RelojPrueba(); }
    }
    static class RelojPrueba extends Clock {
        final AtomicReference<Instant> instante = new AtomicReference<>(Instant.parse("2027-01-01T12:00:00Z"));
        void avanzar(long segundos) { instante.updateAndGet(i -> i.plusSeconds(segundos)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return instante.get(); }
    }
}
