package com.empresa.notificaciones.repository;

import com.empresa.notificaciones.entity.Notificacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Repositorio JPA para notificaciones. Cada servicio gestiona su propia BD;
 * este servicio usa db_notificaciones y no accede a las BD de otros servicios.
 */
@Repository
public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {

    List<Notificacion> findByUsuarioIdOrderByFechaEnvioDesc(String usuarioId);
}
