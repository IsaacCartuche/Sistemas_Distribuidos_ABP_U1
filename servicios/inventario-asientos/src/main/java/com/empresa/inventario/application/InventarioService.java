package com.empresa.inventario.application;

import com.empresa.inventario.api.InventarioDto.*;
import com.empresa.inventario.config.InventarioConfig.Propiedades;
import com.empresa.inventario.domain.*;
import com.empresa.inventario.persistence.*;
import com.empresa.inventario.outbox.ExpiracionOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class InventarioService {
    private final AsientoRepository asientos;
    private final RetencionRepository retenciones;
    private final Clock reloj;
    private final Propiedades propiedades;
    private final ExpiracionOutbox outbox;

    public InventarioService(AsientoRepository asientos, RetencionRepository retenciones,
                             Clock reloj, Propiedades propiedades, ExpiracionOutbox outbox) {
        this.asientos = asientos;
        this.retenciones = retenciones;
        this.reloj = reloj;
        this.propiedades = propiedades;
        this.outbox = outbox;
    }

    public LocalDateTime ahora() {
        return LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public List<AsientoResponse> listar(Integer vueloId, boolean soloDisponibles) {
        LocalDateTime ahora = ahora();
        List<Asiento> lista = soloDisponibles ? asientos.disponibles(vueloId, ahora)
                : asientos.findByVueloIdOrderByNumeroAsiento(vueloId);
        return lista.stream().map(a -> AsientoResponse.from(a, ahora)).toList();
    }

    public AsientoResponse consultar(Integer id) { return AsientoResponse.from(obtener(id), ahora()); }

    @Transactional
    public List<AsientoResponse> inicializar(Integer vueloId, InicializarRequest request) {
        // Orden estable reduce interbloqueos entre inicializaciones simultáneas de listas solapadas.
        request.numeros().stream().map(n -> n.toUpperCase(Locale.ROOT)).distinct().sorted()
                .forEach(n -> asientos.insertarSiAusente(vueloId, n));
        return listar(vueloId, false);
    }

    @Transactional
    public RetencionResponse bloquear(Integer id, UUID token) {
        Asiento asiento = obtener(id);
        LocalDateTime ahora = ahora();
        Optional<Retencion> anterior = retenciones.findById(token);
        if (anterior.isPresent()) {
            Retencion r = anterior.get();
            verificarAsiento(r, id);
            if (r.getEstado() == EstadoRetencion.CONFIRMADA) return RetencionResponse.from(r);
            if (r.getEstado() == EstadoRetencion.ACTIVA && token.equals(asiento.getBloqueoToken())
                    && asiento.getEstado() == EstadoAsiento.BLOQUEADO && r.getExpiraEn().isAfter(ahora)) {
                return RetencionResponse.from(r); // No renueva TTL ni escribe.
            }
            throw conflicto("Token terminado o expirado: no puede reutilizarse");
        }
        if (!asiento.disponible(ahora)) throw conflicto("Asiento no disponible");
        if (asiento.expirado(ahora)) expirarActual(asiento);
        Retencion retencion = new Retencion(token, id, ahora.plusSeconds(propiedades.ttlSegundos()));
        retenciones.save(retencion);
        asiento.bloquear(token, retencion.getExpiraEn());
        asientos.flush(); // Fuerza UPDATE ... WHERE version = X antes de responder.
        return RetencionResponse.from(retencion);
    }

    @Transactional
    public RetencionResponse confirmar(Integer id, UUID token) {
        Asiento asiento = obtener(id);
        Retencion r = retencion(id, token);
        if (r.getEstado() == EstadoRetencion.CONFIRMADA) return RetencionResponse.from(r);
        verificarActual(asiento, r);
        if (!r.getExpiraEn().isAfter(ahora())) throw conflicto("La retención ha expirado");
        asiento.confirmar();
        r.terminar(EstadoRetencion.CONFIRMADA);
        asientos.flush();
        return RetencionResponse.from(r);
    }

    @Transactional
    public RetencionResponse liberar(Integer id, UUID token) {
        Asiento asiento = obtener(id);
        Retencion r = retencion(id, token);
        if (r.getEstado() == EstadoRetencion.LIBERADA || r.getEstado() == EstadoRetencion.EXPIRADA) {
            return RetencionResponse.from(r); // No toca un bloqueo posterior.
        }
        if (r.getEstado() == EstadoRetencion.CONFIRMADA) throw conflicto("Un asiento reservado no se libera por este endpoint");
        verificarActual(asiento, r);
        if (asiento.expirado(ahora())) {
            expirarActual(asiento);
        } else {
            r.terminar(EstadoRetencion.LIBERADA);
            asiento.liberar();
        }
        asientos.flush();
        return RetencionResponse.from(r);
    }

    /** Cada candidato se revalida en una transacción nueva, con control optimista al escribir. */
    @Transactional
    public void expirarUno(Integer id) {
        Asiento asiento = obtener(id);
        if (asiento.expirado(ahora())) {
            expirarActual(asiento);
            asientos.flush();
        }
    }

    private void expirarActual(Asiento asiento) {
        Retencion r = retencion(asiento.getId(), asiento.getBloqueoToken());
        verificarActual(asiento, r);
        r.terminar(EstadoRetencion.EXPIRADA);
        outbox.registrar(asiento, r);
        asiento.liberar();
    }
    private Asiento obtener(Integer id) {
        return asientos.findById(id).orElseThrow(() -> new InventarioException(404, "Asiento no encontrado"));
    }
    private Retencion retencion(Integer id, UUID token) {
        if (token == null) throw conflicto("Bloqueo previo sin token: requiere revisión de datos heredados");
        Retencion r = retenciones.findById(token)
                .orElseThrow(() -> new InventarioException(404, "Retención no encontrada"));
        verificarAsiento(r, id);
        return r;
    }
    private void verificarAsiento(Retencion r, Integer id) {
        if (!r.getAsientoId().equals(id)) throw conflicto("El token pertenece a otro asiento");
    }
    private void verificarActual(Asiento asiento, Retencion r) {
        if (r.getEstado() != EstadoRetencion.ACTIVA || asiento.getEstado() != EstadoAsiento.BLOQUEADO
                || !r.getToken().equals(asiento.getBloqueoToken())) throw conflicto("La retención ya no está activa");
    }
    private InventarioException conflicto(String mensaje) { return new InventarioException(409, mensaje); }
}
