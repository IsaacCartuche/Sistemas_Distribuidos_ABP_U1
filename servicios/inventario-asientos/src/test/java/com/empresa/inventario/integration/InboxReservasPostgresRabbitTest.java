package com.empresa.inventario.integration;

import com.empresa.inventario.api.InventarioDto.InicializarRequest;
import com.empresa.inventario.application.InventarioService;
import com.empresa.inventario.domain.EstadoAsiento;
import com.empresa.inventario.inbox.*;
import com.empresa.inventario.outbox.OutboxWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INVENTARIO_RABBIT_INTEGRATION", matches = "true")
@SpringBootTest(properties = {"inventario.consumidor.habilitado=true", "inventario.outbox.habilitada=false",
        "inventario.expiracion-habilitada=false", "inventario.ttl-segundos=60",
        "spring.rabbitmq.listener.simple.concurrency=2", "spring.datasource.hikari.connection-timeout=1000"})
@Import({InventarioPostgresTest.RelojConfig.class, InboxReservasPostgresRabbitTest.TopologiaPrueba.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InboxReservasPostgresRabbitTest {
    private static final AtomicInteger IDS = new AtomicInteger(30000);
    private static final String ENTRADA = "inventario.entrada";
    private static final String DLQ = "inventario.dlq.pruebas";
    @Autowired InventarioService inventario;
    @Autowired ProcesadorReservas procesador;
    @Autowired ValidadorReserva validador;
    @Autowired ReservasListener listener;
    @Autowired InventarioPostgresTest.RelojPrueba reloj;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired RabbitTemplate rabbit;
    @Autowired CachingConnectionFactory factory;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired PlatformTransactionManager tm;
    @Autowired OutboxWorker outbox;
    private record Pedido(int reserva, int vuelo, String asiento, UUID correlation, UUID token) { }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> requerida("INVENTARIO_TEST_INBOX_DB_URL"));
        r.add("spring.datasource.username", () -> requerida("INVENTARIO_TEST_DB_USER"));
        r.add("spring.datasource.password", () -> requerida("INVENTARIO_TEST_DB_PASSWORD"));
        r.add("spring.rabbitmq.host", () -> "127.0.0.1");
        r.add("spring.rabbitmq.port", () -> requerida("INVENTARIO_TEST_RABBIT_PORT"));
        r.add("spring.rabbitmq.username", () -> "inventario_test");
        r.add("spring.rabbitmq.password", () -> "inventario_test_aislado");
    }
    private static String requerida(String nombre) { return InventarioPostgresTest.requerida(nombre); }

    @BeforeEach
    void preparar() {
        reloj.instante.set(Instant.parse("2027-01-01T12:00:00Z"));
        assertThat(registry.getListenerContainer("inventario-reservas").isRunning()).isTrue();
        while (rabbit.receive(DLQ) != null) { }
        while (rabbit.receive("reservas.alta") != null) { }
    }
    @AfterEach
    void completarPublicacionesDeFixtures() {
        while (outbox.publicarUno()) { }
    }

    @Test
    void recepcionRealBloqueaYPersisteAsociacionAntesDeAck() throws Exception {
        Pedido p = pedido();
        ObjectNode evento = evento(p, "reserva.creada");
        publicar(evento);
        esperarResultado(evento, "ASIENTO_BLOQUEADO");
        assertThat(inventario.consultar(asiento(p)).estado()).isEqualTo(EstadoAsiento.BLOQUEADO);
        assertThat(jdbc.queryForObject("select correlation_id from inventario_solicitudes where token = ?", UUID.class, p.token())).isEqualTo(p.correlation());
        assertThat(jdbc.queryForObject("select reserva_clave from inventario_solicitudes where token = ?", String.class, p.token())).isEqualTo(Integer.toString(p.reserva()));
    }
    @Test
    void asientoOcupadoRegistraResultadoYNoSeReintenta() throws Exception {
        Pedido primero = pedido();
        procesar(evento(primero, "reserva.creada"));
        Pedido otro = otroMismoAsiento(primero);
        ObjectNode evento = evento(otro, "reserva.creada");
        publicar(evento);
        esperarResultado(evento, "ASIENTO_NO_DISPONIBLE");
        assertThat(jdbc.queryForObject("select bloqueo_token from asientos where id = ?", UUID.class, asiento(primero))).isEqualTo(primero.token());
        assertThat(rabbit.receive(DLQ)).isNull();
    }
    @Test
    void dosReservasCompitenYUnaSolaGana() throws Exception {
        Pedido uno = pedido(), dos = otroMismoAsiento(uno);
        var resultados = paralelos(2, i -> reintentar(evento(i == 0 ? uno : dos, "reserva.creada")).codigo());
        assertThat(resultados).containsExactlyInAnyOrder("ASIENTO_BLOQUEADO", "ASIENTO_NO_DISPONIBLE");
        assertThat(inventario.consultar(asiento(uno)).version()).isEqualTo(1);
    }
    @Test
    void mismoEventIdConcurrenteSoloRegistraUnaOperacion() throws Exception {
        Pedido p = pedido();
        ObjectNode evento = evento(p, "reserva.creada");
        var resultados = paralelos(4, i -> procesar(evento));
        assertThat(resultados.stream().filter(ProcesadorReservas.Resultado::duplicado).count()).isEqualTo(3);
        assertThat(inbox(evento)).isEqualTo(1);
        assertThat(inventario.consultar(asiento(p)).version()).isEqualTo(1);
    }
    @Test
    void mismoTokenConEventosDistintosNoRenuevaTtl() {
        Pedido p = pedido();
        procesar(evento(p, "reserva.creada"));
        var vencimiento = inventario.consultar(asiento(p)).bloqueadoHasta();
        reloj.avanzar(30);
        assertThat(procesar(evento(p, "reserva.creada")).codigo()).isEqualTo("ASIENTO_BLOQUEADO");
        assertThat(inventario.consultar(asiento(p)).bloqueadoHasta()).isEqualTo(vencimiento);
        assertThat(inventario.consultar(asiento(p)).version()).isEqualTo(1);
    }
    @Test
    void mismoTokenConEventosConcurrentesSoloBloqueaUnaVez() throws Exception {
        Pedido p = pedido();
        var resultados = paralelos(4, i -> procesar(evento(p, "reserva.creada")).codigo());
        assertThat(resultados).containsOnly("ASIENTO_BLOQUEADO");
        assertThat(inventario.consultar(asiento(p)).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from retenciones where token = ?", Integer.class, p.token())).isEqualTo(1);
    }
    @Test
    void eventIdReutilizadoConOtrosDatosEsErrorPermanente() throws Exception {
        Pedido p = pedido();
        ObjectNode evento = evento(p, "reserva.creada");
        procesar(evento);
        var alterado = evento.deepCopy();
        ((ObjectNode) alterado.get("payload")).put("asiento", "12B");
        publicar(alterado);
        assertThat(rabbit.receive(DLQ, 5000)).isNotNull();
        assertThat(inbox(evento)).isEqualTo(1);
        assertThat(inventario.consultar(asiento(p)).version()).isEqualTo(1);
    }
    @Test
    void mensajeIncompletoVaADlqSinCambiarInventario() {
        Pedido p = pedido();
        ObjectNode evento = evento(p, "reserva.creada");
        ((ObjectNode) evento.get("payload")).remove("token");
        publicar(evento);
        assertThat(rabbit.receive(DLQ, 5000)).isNotNull();
        assertThat(inbox(evento)).isZero();
        assertThat(inventario.consultar(asiento(p)).estado()).isEqualTo(EstadoAsiento.DISPONIBLE);
    }
    @Test
    void cancelarRealYDuplicarNoVuelveATocarAsiento() throws Exception {
        Pedido p = pedido();
        procesar(evento(p, "reserva.creada"));
        ObjectNode cancelacion = evento(p, "reserva.cancelada");
        publicar(cancelacion);
        esperarResultado(cancelacion, "RETENCION_LIBERADA_O_TERMINAL");
        assertThat(procesar(cancelacion).duplicado()).isTrue();
        assertThat(procesar(evento(p, "reserva.cancelada")).codigo()).isEqualTo("RESERVA_YA_CANCELADA");
        assertThat(inventario.consultar(asiento(p)).estado()).isEqualTo(EstadoAsiento.DISPONIBLE);
        assertThat(inventario.consultar(asiento(p)).version()).isEqualTo(2);
    }
    @Test
    void cancelacionAntiguaNoLiberaOtroBloqueo() {
        Pedido viejo = pedido();
        procesar(evento(viejo, "reserva.creada"));
        procesar(evento(viejo, "reserva.cancelada"));
        Pedido nuevo = otroMismoAsiento(viejo);
        procesar(evento(nuevo, "reserva.creada"));
        procesar(evento(viejo, "reserva.cancelada"));
        assertThat(jdbc.queryForObject("select bloqueo_token from asientos where id = ?", UUID.class, asiento(viejo))).isEqualTo(nuevo.token());
        assertThat(inventario.consultar(asiento(viejo)).estado()).isEqualTo(EstadoAsiento.BLOQUEADO);
    }
    @Test
    void cancelarAntesDeCrearImpideCreacionTardiaInclusoOtroToken() {
        Pedido p = pedido();
        assertThat(procesar(evento(p, "reserva.cancelada")).codigo()).isEqualTo("CANCELACION_REGISTRADA_SIN_RETENCION");
        assertThat(procesar(evento(p, "reserva.creada")).codigo()).isEqualTo("RESERVA_YA_CANCELADA");
        Pedido cambiado = new Pedido(p.reserva(), p.vuelo(), p.asiento(), p.correlation(), UUID.randomUUID());
        assertThat(procesar(evento(cambiado, "reserva.creada")).codigo()).isEqualTo("IDENTIDAD_INCOHERENTE");
        assertThat(inventario.consultar(asiento(p)).version()).isZero();
    }
    @Test
    void cancelacionAntesDeInicializarAsientosTambienEsDurable() {
        Pedido p = new Pedido(IDS.incrementAndGet(), IDS.incrementAndGet(), "12A", UUID.randomUUID(), UUID.randomUUID());
        procesar(evento(p, "reserva.cancelada"));
        inventario.inicializar(p.vuelo(), new InicializarRequest(List.of(p.asiento())));
        assertThat(procesar(evento(p, "reserva.creada")).codigo()).isEqualTo("RESERVA_YA_CANCELADA");
        assertThat(inventario.consultar(asiento(p)).disponible()).isTrue();
    }
    @Test
    void creacionYCancelacionSimultaneasTerminanCanceladasSinBloqueoTardio() throws Exception {
        Pedido p = pedido();
        paralelos(2, i -> procesar(evento(p, i == 0 ? "reserva.creada" : "reserva.cancelada")));
        assertThat(jdbc.queryForObject("select estado from inventario_solicitudes where token = ?", String.class, p.token())).isEqualTo("CANCELADA");
        assertThat(inventario.consultar(asiento(p)).estado()).isEqualTo(EstadoAsiento.DISPONIBLE);
        assertThat(procesar(evento(p, "reserva.creada")).codigo()).isEqualTo("RESERVA_YA_CANCELADA");
    }
    @Test
    void cancelarConfirmadaNoImplementaCompensacionImplicita() {
        Pedido p = pedido();
        procesar(evento(p, "reserva.creada"));
        inventario.confirmar(asiento(p), p.token());
        assertThat(procesar(evento(p, "reserva.cancelada")).codigo()).isEqualTo("RESERVADO_NO_LIBERADO");
        assertThat(inventario.consultar(asiento(p)).estado()).isEqualTo(EstadoAsiento.RESERVADO);
    }
    @Test
    void correlacionOReservaAjenasNoAutorizanLiberacion() {
        Pedido p = pedido();
        procesar(evento(p, "reserva.creada"));
        Pedido ajeno = new Pedido(IDS.incrementAndGet(), p.vuelo(), p.asiento(), UUID.randomUUID(), p.token());
        assertThat(procesar(evento(ajeno, "reserva.cancelada")).codigo()).isEqualTo("TOKEN_DE_OTRA_RESERVA");
        Pedido correlacionAjena = new Pedido(p.reserva(), p.vuelo(), p.asiento(), UUID.randomUUID(), p.token());
        assertThat(procesar(evento(correlacionAjena, "reserva.cancelada")).codigo()).isEqualTo("IDENTIDAD_INCOHERENTE");
        assertThat(inventario.consultar(asiento(p)).version()).isEqualTo(1);
    }
    @Test
    void retencionManualNoSeAdoptaPorConocerTokenYExpiraSinContextoInventado() {
        Pedido p = pedido();
        inventario.bloquear(asiento(p), p.token());
        assertThat(procesar(evento(p, "reserva.creada")).codigo()).isEqualTo("TOKEN_PREEXISTENTE_SIN_ASOCIACION");
        assertThat(procesar(evento(p, "reserva.cancelada")).codigo()).isEqualTo("IDENTIDAD_INCOHERENTE");
        reloj.avanzar(60);
        inventario.expirarUno(asiento(p));
        assertThat(jdbc.queryForObject("select estado from inventario_outbox where token = ?", String.class, p.token())).isEqualTo("ESPERA_CONTEXTO");
    }
    @Test
    void ttlVencidoNoSeRenuevaPorOtraCreacion() {
        Pedido p = pedido();
        procesar(evento(p, "reserva.creada"));
        reloj.avanzar(60);
        assertThat(procesar(evento(p, "reserva.creada")).codigo()).isEqualTo("RETENCION_TERMINAL");
        assertThat(inventario.consultar(asiento(p)).version()).isEqualTo(1);
    }
    @Test
    void expiracionTieneContextoAutenticoYPublicacionReal() throws Exception {
        Pedido p = pedido();
        procesar(evento(p, "reserva.creada"));
        reloj.avanzar(60);
        inventario.expirarUno(asiento(p));
        assertThat(jdbc.queryForObject("select estado from inventario_outbox where token = ?", String.class, p.token())).isEqualTo("PENDIENTE");
        var json = mapper.readTree(jdbc.queryForObject("select mensaje from inventario_outbox where token = ?", String.class, p.token()));
        assertThat(json.get("correlationId").asText()).isEqualTo(p.correlation().toString());
        assertThat(json.at("/payload/reservaId").asInt()).isEqualTo(p.reserva());
        assertThat(json.at("/payload/token").asText()).isEqualTo(p.token().toString());
        assertThat(outbox.publicarUno()).isTrue();
        Message recibido = rabbit.receive("reservas.alta", 5000);
        assertThat(recibido).isNotNull();
        assertThat(mapper.readTree(recibido.getBody())).isEqualTo(json);
    }
    @Test
    void rebloqueoTrasTtlConservaContextoAntiguoYCancelacionNoLiberaNuevo() throws Exception {
        Pedido viejo = pedido();
        procesar(evento(viejo, "reserva.creada"));
        reloj.avanzar(60);
        Pedido nuevo = otroMismoAsiento(viejo);
        assertThat(procesar(evento(nuevo, "reserva.creada")).codigo()).isEqualTo("ASIENTO_BLOQUEADO");
        var json = mapper.readTree(jdbc.queryForObject("select mensaje from inventario_outbox where token = ?", String.class, viejo.token()));
        assertThat(json.at("/payload/reservaId").asInt()).isEqualTo(viejo.reserva());
        assertThat(json.get("correlationId").asText()).isEqualTo(viejo.correlation().toString());
        procesar(evento(viejo, "reserva.cancelada"));
        assertThat(jdbc.queryForObject("select bloqueo_token from asientos where id = ?", UUID.class, asiento(nuevo))).isEqualTo(nuevo.token());
        assertThat(inventario.consultar(asiento(nuevo)).estado()).isEqualTo(EstadoAsiento.BLOQUEADO);
    }
    @Test
    void rollbackIncluyeInboxAsociacionYBloqueo() {
        Pedido p = pedido();
        ObjectNode evento = evento(p, "reserva.creada");
        new TransactionTemplate(tm).executeWithoutResult(status -> {
            procesar(evento);
            assertThat(inbox(evento)).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(inbox(evento)).isZero();
        assertThat(inventario.consultar(asiento(p)).version()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inventario_solicitudes where token = ?", Integer.class, p.token())).isZero();
        assertThat(procesar(evento).codigo()).isEqualTo("ASIENTO_BLOQUEADO");
    }
    @Test
    void reentregaRealDespuesDeCommitSinAckEsIdempotente() throws Exception {
        var container = registry.getListenerContainer("inventario-reservas");
        container.stop();
        Pedido p = pedido();
        ObjectNode evento = evento(p, "reserva.creada");
        try {
            publicar(evento);
            rabbit.execute(channel -> {
                var primero = channel.basicGet(ENTRADA, false);
                assertThat(primero).isNotNull();
                listener.recibir(mensaje(primero.getBody(), primero.getEnvelope().getRoutingKey()));
                assertThat(inbox(evento)).isEqualTo(1); // Commit realizado, ACK todavía no enviado.
                channel.basicNack(primero.getEnvelope().getDeliveryTag(), false, true);
                var segundo = channel.basicGet(ENTRADA, false);
                assertThat(segundo).isNotNull();
                assertThat(segundo.getEnvelope().isRedeliver()).isTrue();
                listener.recibir(mensaje(segundo.getBody(), segundo.getEnvelope().getRoutingKey()));
                channel.basicAck(segundo.getEnvelope().getDeliveryTag(), false);
                return null;
            });
        } finally { container.start(); }
        assertThat(inbox(evento)).isEqualTo(1);
        assertThat(inventario.consultar(asiento(p)).version()).isEqualTo(1);
    }
    @Test
    void recuperaMensajePendienteAlReiniciarContenedorListener() throws Exception {
        var container = registry.getListenerContainer("inventario-reservas");
        container.stop();
        Pedido p = pedido();
        ObjectNode evento = evento(p, "reserva.creada");
        try {
            publicar(evento);
            assertThat(inbox(evento)).isZero();
        } finally { container.start(); }
        esperarResultado(evento, "ASIENTO_BLOQUEADO");
    }
    @Test
    void postgresTemporalInaccesibleAgotaReintentosSinAckYSeRecuperaDesdeDlq() throws Exception {
        Pedido p = pedido();
        ObjectNode evento = evento(p, "reserva.creada");
        String postgres = requerida("INVENTARIO_TEST_POSTGRES_CONTAINER");
        assertThat(comando("docker", "inspect", "--format", "{{index .Config.Labels \"proyecto\"}}", postgres).strip())
                .isEqualTo("inventario-integracion");
        Message rechazado;
        comando("docker", "pause", postgres);
        try {
            publicar(evento);
            rechazado = rabbit.receive(DLQ, 15000);
            assertThat(rechazado).isNotNull();
        } finally { comando("docker", "unpause", postgres); }
        assertThat(inbox(evento)).isZero();
        publicar(evento); // Reproceso explícito con el mismo ID tras recuperar PostgreSQL.
        esperarResultado(evento, "ASIENTO_BLOQUEADO");
        assertThat(inventario.consultar(asiento(p)).version()).isEqualTo(1);
    }

    private Pedido pedido() {
        int vuelo = IDS.incrementAndGet();
        inventario.inicializar(vuelo, new InicializarRequest(List.of("12A", "12B")));
        return new Pedido(IDS.incrementAndGet(), vuelo, "12A", UUID.randomUUID(), UUID.randomUUID());
    }
    private Pedido otroMismoAsiento(Pedido p) { return new Pedido(IDS.incrementAndGet(), p.vuelo(), p.asiento(), UUID.randomUUID(), UUID.randomUUID()); }
    private int asiento(Pedido p) { return jdbc.queryForObject("select id from asientos where vuelo_id = ? and numero_asiento = ?", Integer.class, p.vuelo(), p.asiento()); }
    private ObjectNode evento(Pedido p, String tipo) {
        var json = mapper.createObjectNode();
        json.put("eventId", UUID.randomUUID().toString());
        json.put("correlationId", p.correlation().toString());
        json.put("timestamp", reloj.instant().toString());
        json.put("tipo", tipo);
        var payload = json.putObject("payload");
        payload.put("reservaId", p.reserva()); payload.put("vueloId", p.vuelo());
        payload.put("asiento", p.asiento()); payload.put("token", p.token().toString());
        return json;
    }
    private ProcesadorReservas.Resultado procesar(ObjectNode json) {
        return procesador.procesar(validador.validar(json.toString().getBytes(StandardCharsets.UTF_8), json.get("tipo").asText()));
    }
    private ProcesadorReservas.Resultado reintentar(ObjectNode json) {
        for (int n = 0; n < 3; n++) {
            try { return procesar(json); }
            catch (OptimisticLockingFailureException e) { if (n == 2) throw e; }
        }
        throw new AssertionError();
    }
    private void publicar(ObjectNode json) {
        rabbit.send("vuelos.events", json.get("tipo").asText(), mensaje(json.toString().getBytes(StandardCharsets.UTF_8), json.get("tipo").asText()));
    }
    private Message mensaje(byte[] body, String key) {
        var props = new MessageProperties(); props.setContentType("application/json");
        props.setReceivedRoutingKey(key); props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(body, props);
    }
    private int inbox(ObjectNode json) {
        return jdbc.queryForObject("select count(*) from inventario_inbox where consumidor = ? and event_id = ?", Integer.class,
                InboxRepository.CONSUMIDOR, UUID.fromString(json.get("eventId").asText()));
    }
    private void esperarResultado(ObjectNode json, String resultado) throws Exception {
        esperar(() -> jdbc.queryForObject("select count(*) from inventario_inbox where event_id = ? and resultado = ?",
                Integer.class, UUID.fromString(json.get("eventId").asText()), resultado) == 1);
    }
    private void esperar(BooleanSupplier condicion) throws Exception {
        long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < fin) { if (condicion.getAsBoolean()) return; Thread.sleep(25); }
        assertThat(condicion.getAsBoolean()).isTrue();
    }
    private <T> List<T> paralelos(int n, IntFunction<T> accion) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier salida = new CyclicBarrier(n);
        try {
            var futuros = java.util.stream.IntStream.range(0, n).mapToObj(i -> pool.submit(() -> {
                salida.await(10, TimeUnit.SECONDS); return accion.apply(i);
            })).toList();
            var resultados = new java.util.ArrayList<T>();
            for (var f : futuros) resultados.add(f.get(15, TimeUnit.SECONDS));
            return resultados;
        } finally { pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }
    private String comando(String... args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        assertThat(p.waitFor(20, TimeUnit.SECONDS)).isTrue();
        String salida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.exitValue()).as(salida).isZero(); return salida;
    }
    @TestConfiguration
    static class TopologiaPrueba {
        @Bean
        Declarables topologiaInbox() {
            var exchange = new TopicExchange("vuelos.events", true, false);
            var dlx = new TopicExchange("vuelos.dlx", true, false);
            var entrada = new Queue(ENTRADA, true, false, false, Map.of("x-max-priority", 5,
                    "x-dead-letter-exchange", "vuelos.dlx", "x-dead-letter-routing-key", "inventario.dlq"));
            var dlq = new Queue(DLQ, true);
            var reservas = new Queue("reservas.alta", true, false, false, Map.of("x-max-priority", 10));
            return new Declarables(exchange, dlx, entrada, dlq, reservas,
                    BindingBuilder.bind(entrada).to(exchange).with("reserva.creada"),
                    BindingBuilder.bind(entrada).to(exchange).with("reserva.cancelada"),
                    BindingBuilder.bind(dlq).to(dlx).with("inventario.dlq"),
                    BindingBuilder.bind(reservas).to(exchange).with("asiento.expirado"));
        }
    }
}
