package com.empresa.inventario.application;

import com.empresa.inventario.persistence.AsientoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "inventario.expiracion-habilitada", havingValue = "true", matchIfMissing = true)
public class ExpiracionProgramada {
    private static final Logger LOG = LoggerFactory.getLogger(ExpiracionProgramada.class);
    private final AsientoRepository repository;
    private final InventarioService service;
    public ExpiracionProgramada(AsientoRepository repository, InventarioService service) {
        this.repository = repository;
        this.service = service;
    }
    @Scheduled(fixedDelayString = "${inventario.intervalo-expiracion-ms:10000}")
    public void ejecutar() {
        for (Integer id : repository.candidatosExpiracion(service.ahora(), PageRequest.of(0, 100))) {
            try {
                service.expirarUno(id);
            } catch (OptimisticLockingFailureException exception) {
                LOG.debug("Asiento {} modificado concurrentemente; se revalidará en el próximo ciclo", id);
            } catch (InventarioException exception) {
                LOG.warn("No se pudo expirar asiento {}: {}", id, exception.getMessage());
            }
        }
    }
}
