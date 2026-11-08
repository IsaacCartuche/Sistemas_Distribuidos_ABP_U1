package com.empresa.inventario.inbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class ConsumidorDeshabilitadoTest {
    @Test
    void contratoProvisionalNoSeActivaPorDefecto() {
        new ApplicationContextRunner().withUserConfiguration(ConsumidorConfig.class, ReservasListener.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ReservasListener.class);
                    assertThat(context).doesNotHaveBean("inventarioListenerFactory");
                });
    }
}
