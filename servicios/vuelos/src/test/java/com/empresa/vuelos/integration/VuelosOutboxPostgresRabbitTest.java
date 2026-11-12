package com.empresa.vuelos.integration;

import com.empresa.vuelos.api.dto.VueloRequest;
import com.empresa.vuelos.application.VueloService;
import com.empresa.vuelos.domain.EstadoVuelo;
import com.empresa.vuelos.outbox.*;
import com.empresa.vuelos.persistence.VueloRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "VUELOS_RABBIT_INTEGRATION", matches = "true")
@SpringBootTest(properties = {"vuelos.outbox.habilitada=false", "vuelos.outbox.confirm-timeout-ms=1000", "vuelos.outbox.reintento-segundos=1"})
class VuelosOutboxPostgresRabbitTest {
    private static final String COLA = "vuelos.actualizados.pruebas"; // Solo prueba, no contrato de suscriptor.
    @Autowired VueloService service;
    @Autowired VueloRepository vuelos;
    @Autowired VuelosOutboxRepository repository;
    @Autowired VuelosOutboxWorker worker;
    @Autowired RabbitPublicadorVuelos publicador;
    @Autowired VuelosOutboxConfig.Propiedades propiedades;
    @Autowired ValidadorEventoVuelo validador;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired RabbitTemplate rabbit;
    @Autowired CachingConnectionFactory factory;
    @Autowired PlatformTransactionManager tm;
    private RabbitAdmin admin;
    private Binding binding;
    @DynamicPropertySource
    static void base(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requerida("VUELOS_TEST_OUTBOX_DB_URL"));
        registry.add("spring.datasource.username", () -> requerida("VUELOS_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> requerida("VUELOS_TEST_DB_PASSWORD"));
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> requerida("VUELOS_TEST_RABBIT_PORT"));
        registry.add("spring.rabbitmq.username", () -> "vuelos_test");
        registry.add("spring.rabbitmq.password", () -> "vuelos_test_aislado");
        registry.add("spring.rabbitmq.connection-timeout", () -> "1s");
    }
    private static String requerida(String nombre) {
        String valor = System.getenv(nombre);
        if (valor == null || valor.isBlank()) throw new IllegalStateException("Falta " + nombre);
        return valor;
    }
    @BeforeEach void preparar() {
        admin = new RabbitAdmin(factory);
        var exchange = new TopicExchange("vuelos.events", true, false);
        var cola = new Queue(COLA, true);
        admin.declareExchange(exchange); admin.declareQueue(cola);
        binding = BindingBuilder.bind(cola).to(exchange).with("vuelos.actualizados");
        admin.declareBinding(binding);
        while (rabbit.receive(COLA) != null) { }
    }
    @AfterEach void completarFixturesPendientes() { while (worker.publicarUno()) { } }

    @Test void crearConsultarYActualizarIdenticoNoGeneranEvento() {
        int id = nuevo();
        service.consultar(id); service.listar(null, null, null);
        service.actualizar(id, request("125.50"));
        assertThat(version(id)).isZero(); assertThat(eventos(id)).isZero();
    }
    @Test void eventoContieneSnapshotRealRevisionCorrectaYSinDisponibilidad() throws Exception {
        int id = nuevo(); service.actualizar(id, request("130.00"));
        JsonNode evento = mensaje(eventoId(id));
        assertThat(version(id)).isEqualTo(1);
        assertThat(evento.at("/payload/revision").asInt()).isEqualTo(version(id));
        assertThat(evento.at("/payload/precio").decimalValue()).isEqualByComparingTo("130.00");
        assertThat(evento.at("/payload/fechaSalida").asText()).isEqualTo("2027-05-10");
        assertThat(evento.at("/payload/horaSalida").asText()).isEqualTo("09:30");
        assertThat(evento.get("payload").has("capacidadDisponible")).isFalse();
        assertThat(evento.get("payload").has("token")).isFalse();
        assertThat(evento.get("payload").has("reservaId")).isFalse();
        assertThat(evento.get("eventId").asText()).isNotEqualTo(evento.get("correlationId").asText());
        service.actualizar(id, request("130.0"));
        assertThat(eventos(id)).isEqualTo(1); assertThat(version(id)).isEqualTo(1);
    }
    @Test void cambiarSoloDatoInformativoNoLoConvierteEnDisponibilidadAutoritativa() throws Exception {
        int id = nuevo();
        var r = request("125.50");
        service.actualizar(id, new VueloRequest(r.origen(), r.destino(), r.fechaSalida(), r.horaSalida(), r.precio(), r.estado(), 100, 70));
        assertThat(version(id)).isEqualTo(1);
        assertThat(mensaje(eventoId(id)).get("payload").has("capacidadDisponible")).isFalse();
        assertThat(service.consultar(id).capacidadDisponible()).isEqualTo(70);
    }
    @ParameterizedTest
    @ValueSource(strings = {"09:30:00.123456789", "23:59:59.999999999"})
    void precisionDelMapperNoCreaEventosRepetidos(String hora) throws Exception {
        int id = nuevo(); var base = request("125.50");
        var cambio = new VueloRequest(base.origen(), base.destino(), base.fechaSalida(), LocalTime.parse(hora),
                base.precio(), base.estado(), base.capacidadTotal(), base.capacidadDisponible());
        service.actualizar(id, cambio);
        assertThat(mensaje(eventoId(id)).at("/payload/horaSalida").asText()).isEqualTo(service.consultar(id).horaSalida().toString());
        service.actualizar(id, cambio);
        assertThat(version(id)).isEqualTo(1); assertThat(eventos(id)).isEqualTo(1);
    }
    @Test void rollbackRevierteVueloRevisionYOutbox() {
        int id = nuevo();
        new TransactionTemplate(tm).executeWithoutResult(status -> {
            service.actualizar(id, request("130.00")); assertThat(eventos(id)).isEqualTo(1); status.setRollbackOnly();
        });
        assertThat(eventos(id)).isZero(); assertThat(version(id)).isZero();
        assertThat(service.consultar(id).precio()).isEqualByComparingTo("125.50");
    }
    @Test void dosLecturasConcurrentesDeMismaVersionSoloPermitenUnCommit() throws Exception {
        int id = nuevo(); var barrera = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var futuros = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                try {
                    new TransactionTemplate(tm).executeWithoutResult(status -> {
                        vuelos.findById(id).orElseThrow(); esperar(barrera);
                        service.actualizar(id, request(i == 0 ? "140.00" : "150.00"));
                    });
                    return true;
                } catch (OptimisticLockingFailureException exception) { return false; }
            })).toList();
            assertThat(List.of(futuros.get(0).get(15, TimeUnit.SECONDS), futuros.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally { pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
        assertThat(version(id)).isEqualTo(1); assertThat(eventos(id)).isEqualTo(1);
        assertThat(mensaje(eventoId(id)).at("/payload/precio").decimalValue()).isEqualByComparingTo(service.consultar(id).precio());
    }
    @Test void publicacionRealConAckMantieneCuerpoEIdentidad() throws Exception {
        UUID id = pendiente(); String original = cuerpo(id);
        assertThat(worker.publicarUno()).isTrue(); assertThat(estado(id)).isEqualTo("ENVIADO");
        Message recibido = rabbit.receive(COLA, 3000); assertThat(recibido).isNotNull();
        assertThat(new String(recibido.getBody(), StandardCharsets.UTF_8)).isEqualTo(original);
        assertThat(recibido.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(recibido.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(worker.publicarUno()).isFalse();
    }
    @Test void ausenciaDeBindingProduceReturnYNoMarcaEnviado() throws Exception {
        UUID id = pendiente(); String original = cuerpo(id); admin.removeBinding(binding);
        try {
            worker.publicarUno(); assertThat(estado(id)).isEqualTo("PENDIENTE");
            assertThat(error(id)).isEqualTo("SIN_DESTINO"); assertThat(rabbit.receive(COLA)).isNull();
        } finally { admin.declareBinding(binding); }
        habilitar(id); worker.publicarUno();
        assertThat(estado(id)).isEqualTo("ENVIADO"); assertThat(intentos(id)).isEqualTo(2);
        assertThat(new String(rabbit.receive(COLA, 3000).getBody(), StandardCharsets.UTF_8)).isEqualTo(original);
    }
    @Test void brokerInaccesibleConservaPendienteYComponenteReiniciadoRecupera() throws Exception {
        UUID id = pendiente(); String original = cuerpo(id);
        String container = requerida("VUELOS_TEST_RABBIT_CONTAINER");
        assertThat(comando("docker", "inspect", "--format", "{{index .Config.Labels \"proyecto\"}}", container).strip()).isEqualTo("vuelos-outbox-integracion");
        comando("docker", "pause", container);
        try {
            worker.publicarUno(); assertThat(estado(id)).isEqualTo("PENDIENTE");
            assertThat(error(id)).isIn("CONFIRM_TIMEOUT", "BROKER_NO_DISPONIBLE");
        } finally { comando("docker", "unpause", container); factory.resetConnection(); }
        habilitar(id);
        var reiniciado = new VuelosOutboxWorker(repository, new RabbitPublicadorVuelos(rabbit, validador, propiedades), propiedades);
        new TransactionTemplate(tm).executeWithoutResult(status -> reiniciado.publicarUno());
        assertThat(estado(id)).isEqualTo("ENVIADO");
        Message recibido = rabbit.receive(COLA, 3000); assertThat(recibido).isNotNull();
        assertThat(new String(recibido.getBody(), StandardCharsets.UTF_8)).isEqualTo(original);
        Message duplicado;
        while ((duplicado = rabbit.receive(COLA)) != null) assertThat(duplicado.getMessageProperties().getMessageId()).isEqualTo(id.toString());
    }
    @Test void caidaTrasAckAntesDeCommitPermiteReentregaIdentica() {
        UUID id = pendiente();
        var sinProxy = new VuelosOutboxWorker(repository, publicador, propiedades);
        new TransactionTemplate(tm).executeWithoutResult(status -> { sinProxy.publicarUno(); status.setRollbackOnly(); });
        assertThat(estado(id)).isEqualTo("PENDIENTE"); worker.publicarUno();
        Message primero = rabbit.receive(COLA, 3000), segundo = rabbit.receive(COLA, 3000);
        assertThat(primero).isNotNull(); assertThat(segundo).isNotNull();
        assertThat(primero.getBody()).isEqualTo(segundo.getBody());
        assertThat(primero.getMessageProperties().getMessageId()).isEqualTo(id.toString());
    }
    @Test void dosWorkersNoReclamanLaMismaFila() throws Exception {
        UUID id = pendiente(); var reclamado = new CountDownLatch(1); var continuar = new CountDownLatch(1);
        var primero = new VuelosOutboxWorker(repository, evento -> {
            reclamado.countDown(); esperar(continuar); publicador.publicar(evento);
        }, propiedades);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var envio = pool.submit(() -> new TransactionTemplate(tm).execute(status -> primero.publicarUno()));
            assertThat(reclamado.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.publicarUno()).isFalse(); continuar.countDown();
            assertThat(envio.get(15, TimeUnit.SECONDS)).isTrue();
        } finally { continuar.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
        assertThat(estado(id)).isEqualTo("ENVIADO"); assertThat(intentos(id)).isEqualTo(1);
        assertThat(rabbit.receive(COLA, 3000)).isNotNull(); assertThat(rabbit.receive(COLA)).isNull();
    }
    @ParameterizedTest
    @ValueSource(strings = {"json", "revision", "vueloId", "correlationId", "precio", "capacidadDisponible"})
    void corrupcionPermanenteNoPublicaNiSeReintenta(String defecto) throws Exception {
        UUID id = pendiente(); var json = (com.fasterxml.jackson.databind.node.ObjectNode) mensaje(id);
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.get("payload");
        switch (defecto) {
            case "revision", "vueloId" -> payload.put(defecto, Integer.MAX_VALUE);
            case "correlationId" -> json.put("correlationId", UUID.randomUUID().toString());
            case "precio" -> payload.putNull("precio");
            case "capacidadDisponible" -> payload.put("capacidadDisponible", 80);
            default -> { }
        }
        String corrupto = defecto.equals("json") ? "{" : json.toString();
        jdbc.update("update vuelos_outbox set mensaje = ? where event_id = ?", corrupto, id);
        worker.publicarUno(); assertThat(estado(id)).isEqualTo("ERROR_DATOS");
        assertThat(cuerpo(id)).isEqualTo(corrupto); assertThat(intentos(id)).isEqualTo(1);
        assertThat(worker.publicarUno()).isFalse(); assertThat(rabbit.receive(COLA)).isNull();
    }

    private int nuevo() { return service.crear(request("125.50")).id(); }
    private UUID pendiente() { int id = nuevo(); service.actualizar(id, request("130.00")); return eventoId(id); }
    private VueloRequest request(String precio) {
        return new VueloRequest("Quito", "Loja", LocalDate.of(2027, 5, 10), LocalTime.of(9, 30), new BigDecimal(precio), EstadoVuelo.ACTIVO, 100, 80);
    }
    private int version(int id) { return jdbc.queryForObject("select version from vuelos where id = ?", Integer.class, id); }
    private int eventos(int id) { return jdbc.queryForObject("select count(*) from vuelos_outbox where vuelo_id = ?", Integer.class, id); }
    private UUID eventoId(int id) { return jdbc.queryForObject("select event_id from vuelos_outbox where vuelo_id = ?", UUID.class, id); }
    private String cuerpo(UUID id) { return jdbc.queryForObject("select mensaje from vuelos_outbox where event_id = ?", String.class, id); }
    private JsonNode mensaje(UUID id) throws Exception { return mapper.readTree(cuerpo(id)); }
    private String estado(UUID id) { return jdbc.queryForObject("select estado from vuelos_outbox where event_id = ?", String.class, id); }
    private String error(UUID id) { return jdbc.queryForObject("select ultimo_error from vuelos_outbox where event_id = ?", String.class, id); }
    private int intentos(UUID id) { return jdbc.queryForObject("select intentos from vuelos_outbox where event_id = ?", Integer.class, id); }
    private void habilitar(UUID id) { jdbc.update("update vuelos_outbox set siguiente_intento = CURRENT_TIMESTAMP where event_id = ?", id); }
    private static void esperar(CyclicBarrier barrera) {
        try { barrera.await(10, TimeUnit.SECONDS); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static void esperar(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout barrera"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private String comando(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        String salida = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(salida).isZero(); return salida;
    }
}
