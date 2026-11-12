package com.empresa.inventario.integration;

import com.empresa.inventario.api.InventarioDto.InicializarRequest;
import com.empresa.inventario.application.InventarioService;
import com.empresa.inventario.domain.EstadoAsiento;
import com.empresa.inventario.outbox.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INVENTARIO_RABBIT_INTEGRATION", matches = "true")
@SpringBootTest(properties = {"inventario.expiracion-habilitada=false", "inventario.outbox.habilitada=false",
        "inventario.ttl-segundos=60", "inventario.outbox.confirm-timeout-ms=1000", "inventario.outbox.reintento-segundos=1"})
@Import(InventarioPostgresTest.RelojConfig.class)
class OutboxRabbitPostgresTest {
    private static final AtomicInteger VUELOS = new AtomicInteger(20000);
    private static final String COLA = "reservas.alta";
    @Autowired InventarioService inventario;
    @Autowired InventarioPostgresTest.RelojPrueba reloj;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ContextoOutbox contexto;
    @Autowired OutboxRepository repository;
    @Autowired OutboxWorker worker;
    @Autowired RabbitPublicadorExpiracion publicador;
    @Autowired OutboxConfig.Propiedades propiedades;
    @Autowired ValidadorExpiracion validador;
    @Autowired RabbitTemplate rabbit;
    @Autowired CachingConnectionFactory factory;
    @Autowired PlatformTransactionManager tm;
    private RabbitAdmin admin;
    private Binding binding;

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requerida("INVENTARIO_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> requerida("INVENTARIO_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> requerida("INVENTARIO_TEST_DB_PASSWORD"));
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> requerida("INVENTARIO_TEST_RABBIT_PORT"));
        registry.add("spring.rabbitmq.username", () -> "inventario_test");
        registry.add("spring.rabbitmq.password", () -> "inventario_test_aislado");
        registry.add("spring.rabbitmq.connection-timeout", () -> "1s");
    }
    static String requerida(String nombre) { return InventarioPostgresTest.requerida(nombre); }

    @BeforeEach
    void prepararTopologiaSoloEnBrokerTemporal() {
        // El script exige un broker aislado; nunca declara topología en el broker del proyecto.
        reloj.instante.set(Instant.parse("2027-01-01T12:00:00Z"));
        admin = new RabbitAdmin(factory);
        TopicExchange exchange = new TopicExchange("vuelos.events", true, false);
        Queue cola = new Queue(COLA, true, false, false, Map.of("x-max-priority", 10));
        admin.declareExchange(exchange);
        admin.declareQueue(cola);
        binding = BindingBuilder.bind(cola).to(exchange).with("asiento.expirado");
        admin.declareBinding(binding);
        Queue notificaciones = new Queue("notificaciones.baja", true);
        admin.declareQueue(notificaciones);
        admin.declareBinding(BindingBuilder.bind(notificaciones).to(exchange).with("notificacion.baja"));
        while (rabbit.receive(COLA) != null) { /* Mensajes de pruebas anteriores, solo broker temporal. */ }
    }

    @Test
    void expiracionSinContextoSeConservaYNoPublica() throws Exception {
        UUID id = expirar();
        JsonNode mensaje = mensaje(id);
        assertThat(estado(id)).isEqualTo("ESPERA_CONTEXTO");
        assertThat(mensaje.get("correlationId").isNull()).isTrue();
        assertThat(mensaje.at("/payload/reservaId").isNull()).isTrue();
        assertThat(mensaje.at("/payload/asiento").asText()).isEqualTo("12A");
        assertThat(worker.publicarUno()).isFalse();
        assertThat(rabbit.receive(COLA)).isNull();
    }

    @Test
    void publicacionRealConfirmadaRespetaContratoYSoloReservasRecibeToken() throws Exception {
        UUID id = pendiente();
        String cuerpo = jdbc.queryForObject("select mensaje from inventario_outbox where event_id = ?", String.class, id);
        assertThat(worker.publicarUno()).isTrue();
        assertThat(estado(id)).isEqualTo("ENVIADO");
        Message recibido = rabbit.receive(COLA, 3000);
        assertThat(recibido).isNotNull();
        assertThat(new String(recibido.getBody(), StandardCharsets.UTF_8)).isEqualTo(cuerpo);
        assertThat(recibido.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(recibido.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(mapper.readTree(recibido.getBody()).at("/payload/reservaId").asInt()).isEqualTo(123);
        assertThat(rabbit.receive("notificaciones.baja")).isNull();
        assertThat(worker.publicarUno()).isFalse();
    }

    @Test
    void returnPorFaltaDeBindingNoMarcaEnviadoYReintentaMismoEventId() throws Exception {
        UUID id = pendiente();
        String cuerpo = mensaje(id).toString();
        admin.removeBinding(binding);
        try {
            worker.publicarUno();
            assertThat(estado(id)).isEqualTo("PENDIENTE");
            assertThat(error(id)).isEqualTo("SIN_DESTINO");
            assertThat(rabbit.receive(COLA)).isNull();
        } finally { admin.declareBinding(binding); }
        habilitarReintento(id);
        worker.publicarUno();
        assertThat(estado(id)).isEqualTo("ENVIADO");
        assertThat(mapper.readTree(rabbit.receive(COLA, 3000).getBody()).toString()).isEqualTo(cuerpo);
        assertThat(intentos(id)).isEqualTo(2);
    }

    @Test
    void brokerTemporalPausadoConservaPendienteYSeRecuperaTrasRecrearPublicador() throws Exception {
        UUID id = pendiente();
        String container = requerida("INVENTARIO_TEST_RABBIT_CONTAINER");
        assertThat(comando("docker", "inspect", "--format", "{{index .Config.Labels \"proyecto\"}}", container).strip())
                .isEqualTo("inventario-outbox-integracion");
        comando("docker", "pause", container);
        try {
            worker.publicarUno();
            assertThat(estado(id)).isEqualTo("PENDIENTE");
            assertThat(error(id)).isIn("CONFIRM_TIMEOUT", "BROKER_NO_DISPONIBLE");
        } finally {
            comando("docker", "unpause", container);
            factory.resetConnection();
        }
        habilitarReintento(id);
        // Instancias nuevas sin estado en memoria; recuperación exclusivamente desde PostgreSQL.
        RabbitPublicadorExpiracion reiniciado = new RabbitPublicadorExpiracion(rabbit, propiedades, validador);
        OutboxWorker nuevoWorker = new OutboxWorker(repository, reiniciado, propiedades);
        new TransactionTemplate(tm).executeWithoutResult(status -> nuevoWorker.publicarUno());
        assertThat(estado(id)).isEqualTo("ENVIADO");
        Message recibido = rabbit.receive(COLA, 3000);
        assertThat(recibido).isNotNull();
        assertThat(recibido.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        Message duplicado;
        while ((duplicado = rabbit.receive(COLA)) != null) {
            // Un timeout puede ocurrir DESPUÉS de que el broker acepte: duplicados de transporte legítimos.
            assertThat(duplicado.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        }
        assertThat(intentos(id)).isEqualTo(2);
    }

    @Test
    void caidaDespuesDeAckAntesDelCommitReentregaMismoMensaje() throws Exception {
        // Worker sin proxy dentro de una transacción explícita: simula caída tras ACK antes del commit.
        UUID caida = pendiente();
        OutboxWorker sinProxy = new OutboxWorker(repository, publicador, propiedades);
        new TransactionTemplate(tm).executeWithoutResult(status -> {
            sinProxy.publicarUno();
            status.setRollbackOnly();
        });
        assertThat(estado(caida)).isEqualTo("PENDIENTE");
        assertThat(intentos(caida)).isZero();
        worker.publicarUno();
        assertThat(estado(caida)).isEqualTo("ENVIADO");
        Message primero = rabbit.receive(COLA, 3000);
        Message segundo = rabbit.receive(COLA, 3000);
        assertThat(primero).isNotNull();
        assertThat(segundo).isNotNull();
        assertThat(primero.getBody()).isEqualTo(segundo.getBody());
        assertThat(primero.getMessageProperties().getMessageId()).isEqualTo(caida.toString());
    }

    @Test
    void dosWorkersConTransaccionesRealesNoReclamanLaMismaFila() throws Exception {
        UUID id = pendiente();
        CountDownLatch reclamado = new CountDownLatch(1);
        CountDownLatch continuar = new CountDownLatch(1);
        OutboxWorker primero = new OutboxWorker(repository, evento -> {
            reclamado.countDown();
            try {
                if (!continuar.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout barrera");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            publicador.publicar(evento);
        }, propiedades);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> enviado = pool.submit(() -> new TransactionTemplate(tm).execute(s -> primero.publicarUno()));
            assertThat(reclamado.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.publicarUno()).isFalse(); // SKIP LOCKED, mientras el primer worker aún retiene el lock.
            continuar.countDown();
            assertThat(enviado.get(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            continuar.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertThat(estado(id)).isEqualTo("ENVIADO");
        assertThat(intentos(id)).isEqualTo(1);
        assertThat(rabbit.receive(COLA, 3000)).isNotNull();
        assertThat(rabbit.receive(COLA)).isNull();
    }

    @Test
    void completarContextoConservaIdYTimestampYNoPermiteReasignar() throws Exception {
        UUID id = expirar();
        JsonNode antes = mensaje(id);
        UUID correlation = UUID.randomUUID();
        assertThat(contexto.completar(id, mapper.valueToTree(123), correlation)).isTrue();
        assertThat(contexto.completar(id, mapper.valueToTree(999), UUID.randomUUID())).isFalse();
        JsonNode despues = mensaje(id);
        assertThat(despues.get("eventId")).isEqualTo(antes.get("eventId"));
        assertThat(despues.get("timestamp")).isEqualTo(antes.get("timestamp"));
        assertThat(despues.get("correlationId").asText()).isEqualTo(correlation.toString());
        worker.publicarUno();
        assertThat(estado(id)).isEqualTo("ENVIADO");
        assertThat(rabbit.receive(COLA, 3000)).isNotNull();
    }

    @Test
    void rechazaContextoFicticioOAusente() {
        UUID id = expirar();
        assertThatThrownBy(() -> contexto.completar(id, null, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contexto.completar(id, mapper.valueToTree(123), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(estado(id)).isEqualTo("ESPERA_CONTEXTO");
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "eventId", "correlationId", "token", "reservaId", "timestamp", "vueloId", "asiento", "columnaCorrelation"})
    void mensajeCorruptoSeConservaEnErrorSinPublicarNiReintentar(String defecto) throws Exception {
        UUID id = pendiente();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mensaje(id);
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.get("payload");
        switch (defecto) {
            case "eventId", "correlationId" -> json.put(defecto, UUID.randomUUID().toString());
            case "token" -> payload.put("token", UUID.randomUUID().toString());
            case "reservaId" -> payload.putNull("reservaId");
            case "timestamp" -> json.put("timestamp", "2026-11-07T21:00:00");
            case "vueloId" -> payload.put("vueloId", "1");
            case "asiento" -> payload.remove("asiento");
            case "columnaCorrelation" -> jdbc.update("update inventario_outbox set correlation_id = ? where event_id = ?", UUID.randomUUID(), id);
            default -> { }
        }
        String corrupto = defecto.equals("json") ? "{no-es-json" : json.toString();
        jdbc.update("update inventario_outbox set mensaje = ? where event_id = ?", corrupto, id);
        assertThat(worker.publicarUno()).isTrue();
        assertThat(estado(id)).isEqualTo("ERROR_DATOS");
        assertThat(error(id)).isNotBlank();
        assertThat(intentos(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select mensaje from inventario_outbox where event_id = ?", String.class, id)).isEqualTo(corrupto);
        assertThat(jdbc.queryForObject("select enviado_en from inventario_outbox where event_id = ?", java.sql.Timestamp.class, id)).isNull();
        assertThat(worker.publicarUno()).isFalse();
        assertThat(intentos(id)).isEqualTo(1);
        assertThat(rabbit.receive(COLA)).isNull();
        assertThat(contexto.completar(id, mapper.valueToTree(123), UUID.randomUUID())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "token", "contexto"})
    void completarNoReparaEventosCorruptosEnEspera(String defecto) throws Exception {
        UUID id = expirar();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mensaje(id);
        if (defecto.equals("token")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("payload")).put("token", UUID.randomUUID().toString());
        } else if (defecto.equals("contexto")) {
            json.put("correlationId", UUID.randomUUID().toString());
        }
        String corrupto = defecto.equals("json") ? "{" : json.toString();
        jdbc.update("update inventario_outbox set mensaje = ? where event_id = ?", corrupto, id);
        assertThatThrownBy(() -> contexto.completar(id, mapper.valueToTree(123), UUID.randomUUID()))
                .isInstanceOf(PublicadorExpiracion.FalloDatos.class);
        assertThat(estado(id)).isEqualTo("ESPERA_CONTEXTO");
        assertThat(jdbc.queryForObject("select mensaje from inventario_outbox where event_id = ?", String.class, id)).isEqualTo(corrupto);
        assertThat(worker.publicarUno()).isFalse();
        assertThat(rabbit.receive(COLA)).isNull();
    }

    private UUID expirar() {
        int asiento = inventario.inicializar(VUELOS.incrementAndGet(), new InicializarRequest(List.of("12A"))).get(0).id();
        UUID token = UUID.randomUUID();
        inventario.bloquear(asiento, token);
        reloj.avanzar(60);
        inventario.expirarUno(asiento);
        assertThat(inventario.consultar(asiento).estado()).isEqualTo(EstadoAsiento.DISPONIBLE);
        return jdbc.queryForObject("select event_id from inventario_outbox where token = ?", UUID.class, token);
    }
    private UUID pendiente() {
        UUID id = expirar();
        // IDs explícitos de fixture: nunca se generan para suplir reservas de producción.
        assertThat(contexto.completar(id, mapper.valueToTree(123), UUID.randomUUID())).isTrue();
        return id;
    }
    private JsonNode mensaje(UUID id) throws Exception {
        return mapper.readTree(jdbc.queryForObject("select mensaje from inventario_outbox where event_id = ?", String.class, id));
    }
    private String estado(UUID id) { return jdbc.queryForObject("select estado from inventario_outbox where event_id = ?", String.class, id); }
    private String error(UUID id) { return jdbc.queryForObject("select ultimo_error from inventario_outbox where event_id = ?", String.class, id); }
    private int intentos(UUID id) { return jdbc.queryForObject("select intentos from inventario_outbox where event_id = ?", Integer.class, id); }
    private void habilitarReintento(UUID id) {
        jdbc.update("update inventario_outbox set siguiente_intento = CURRENT_TIMESTAMP where event_id = ?", id);
    }
    private String comando(String... argumentos) throws Exception {
        Process process = new ProcessBuilder(argumentos).redirectErrorStream(true).start();
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }
}
