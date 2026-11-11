package com.empresa.pagos.saga;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import java.util.UUID;
/** Simulador local puro; nunca realiza operaciones de red ni maneja tarjetas. */
@Component
public class PasarelaSimulada {
 private final BigDecimal limite;
 public PasarelaSimulada(@Value("${pagos.simulador.limite:10000.00}") BigDecimal limite){this.limite=limite;}
 public boolean cobrar(UUID clave,BigDecimal monto){return monto.compareTo(limite)<=0;}
 public String reembolsar(UUID clave){return "sim-reembolso-"+clave;}
}
