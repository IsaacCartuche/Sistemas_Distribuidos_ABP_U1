package com.empresa.inventario.persistence;

import com.empresa.inventario.domain.Retencion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface RetencionRepository extends JpaRepository<Retencion, UUID> { }
