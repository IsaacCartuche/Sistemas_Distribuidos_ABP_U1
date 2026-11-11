package com.empresa.pagos.repository;
import com.empresa.pagos.entity.Pago;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PagoRepository extends JpaRepository<Pago,UUID> {}
