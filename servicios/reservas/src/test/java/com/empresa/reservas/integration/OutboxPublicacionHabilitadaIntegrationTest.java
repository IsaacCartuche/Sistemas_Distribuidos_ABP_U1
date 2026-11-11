package com.empresa.reservas.integration;

import com.empresa.reservas.service.OutboxPublicacionScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "outbox.publicacion.habilitada=true",
        "outbox.publicacion.espera-inicial-ms=3600000",
        "outbox.recuperacion.espera-inicial-ms=3600000"
})
class OutboxPublicacionHabilitadaIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void debeRegistrarSchedulerCuandoEstaHabilitado() {
        assertEquals(
                1,
                context.getBeansOfType(OutboxPublicacionScheduler.class).size()
        );
    }
}
