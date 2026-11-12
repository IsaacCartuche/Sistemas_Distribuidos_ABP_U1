import { useState, useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { vuelosService } from '../services/api';
import { authService } from '../services/api';

export default function Vuelos() {
  const [vuelos, setVuelos] = useState([]);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState('');
  const [searchParams] = useSearchParams();
  const usuario = authService.getUsuario();

  useEffect(() => {
    const cargar = () => {
      vuelosService
        .listar()
        .then((res) => setVuelos(res.data || []))
        .catch((err) =>
          setError(
            err?.response?.status
              ? `No se pudo consultar el servicio de vuelos (${err.response.status})`
              : 'No se pudo consultar el servicio de vuelos'
          )
        )
        .finally(() => setCargando(false));
    };

    cargar();
    window.addEventListener('reserva-creada', cargar);

    return () => window.removeEventListener('reserva-creada', cargar);
  }, []);

  const badgeEstado = (estado) => {
    switch (estado?.toLowerCase()) {
      case 'activo': return '<span class="badge success">● Activo</span>';
      case 'retrasado': return '<span class="badge danger">● Retrasado</span>';
      case 'pocos asientos': return '<span class="badge warning">○ Pocos asientos</span>';
      default: return '<span class="badge">● Activo</span>';
    }
  };

  if (cargando) return (
    <div className="card fade-in" style={{ maxWidth: 400, margin: 'var(--space-8) auto' }}>
      <p className="muted" style={{ textAlign: 'center' }}>Cargando vuelos...</p>
    </div>
  );

  return (
    <div style={{ padding: 'var(--space-6)' }}>
      <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 'var(--space-6)', paddingBottom: 'var(--space-4)', borderBottom: '1px solid var(--color-border)' }}>
        <div>
          <div style={{ fontSize: 'var(--text-2xl)', fontWeight: 'var(--fw-semibold)', color: 'var(--color-ink)', letterSpacing: '-0.01em' }}>Vuelos disponibles</div>
          <div style={{ fontSize: 'var(--text-sm)', color: 'var(--color-text-muted)', marginTop: 'var(--space-1)' }}>Estado en tiempo real</div>
        </div>
        {usuario && (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--space-2)', padding: 'var(--space-2) var(--space-4)', background: 'var(--color-surface-hover)', border: '1px solid var(--color-border)', borderRadius: 'var(--radius-full)', fontSize: 'var(--text-sm)', color: 'var(--color-ink)' }}>
            📅 {new Date().toLocaleDateString('es-ES', { weekday: 'short', day: '2-digit', month: 'short' })}
          </span>
        )}
      </header>

      {error && <p className="error">{error}</p>}

      {!error && vuelos.length === 0 && (
        <div style={{ textAlign: 'center', padding: 'var(--space-20)', color: 'var(--color-text-muted)' }}>
          <h3 style={{ fontSize: 'var(--text-xl)', color: 'var(--color-ink)', marginBottom: 'var(--space-2)' }}>No hay vuelos disponibles</h3>
          <p style={{ maxWidth: '400px', margin: '0 auto', lineHeight: '1.6' }}>Intenta nuevamente más tarde.</p>
        </div>
      )}

      {vuelos.length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 'var(--space-4)' }}>
          {vuelos.map((v) => {
            const disponibles = Number(v.capacidadDisponible || 0);
            const total = Number(v.capacidadTotal || 0);
            const lleno = disponibles <= 0;
            const estado = v.estado?.toLowerCase() || 'activo';
            return (
              <div key={v.id} className="card fade-in" style={{ border: lleno ? '1px solid #d1d5db' : '1px solid var(--color-border)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--space-3)' }}>
                  <span className="badge" style={{ background: '#eff6ff', color: 'var(--color-primary)', borderColor: 'rgba(37,99,235,0.2)' }}>{v.id}</span>
                  <span dangerouslySetInnerHTML={{ __html: badgeEstado(estado) }} />
                </div>
                <div style={{ fontWeight: 'var(--fw-semibold)', fontSize: 'var(--text-lg)', color: 'var(--color-ink)', marginBottom: 'var(--space-1)' }}>
                  {v.origen} → {v.destino}
                </div>
                <div style={{ fontSize: 'var(--text-xs)', color: 'var(--color-text-muted)', marginBottom: 'var(--space-3)' }}>
                  {v.codigoOrigen} → {v.codigoDestino}
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-2)', marginBottom: 'var(--space-4)' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)' }}>
                    <span style={{ color: 'var(--color-text-muted)', fontSize: 'var(--text-xs)' }}>{v.horaSalida}</span>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)' }}>
                    <span style={{ color: 'var(--color-text-muted)', fontSize: 'var(--text-xs)' }}>{v.horaLlegada}</span>
                  </div>
                </div>
                <div style={{ marginBottom: 'var(--space-3)' }}>
                  <div style={{ fontSize: 'var(--text-xs)', color: 'var(--color-text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 'var(--space-1)' }}>Asientos disponibles</div>
                  <div style={{ fontSize: 'var(--text-base)', fontWeight: 'var(--fw-bold)', color: lleno ? 'var(--color-danger)' : 'var(--color-ink)' }}>
                    {disponibles} de {total}
                  </div>
                </div>
                <div style={{ marginBottom: 'var(--space-3)' }}>
                  <div style={{ fontSize: 'var(--text-xs)', color: 'var(--color-text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 'var(--space-1)' }}>Precio</div>
                  <div style={{ fontSize: 'var(--text-lg)', fontWeight: 'var(--fw-bold)', color: 'var(--color-primary)' }}>
                    ${v.precio}
                  </div>
                </div>
                {usuario && !lleno ? (
                  <Link
                    to={`/reservas?vuelo=${v.id}&asiento=${v.asientoDisponible || 'A1'}`}
                    className="btn btn-primary btn-full"
                  >
                    Reservar
                  </Link>
                ) : lleno ? (
                  <span className="btn btn-primary btn-full" style={{ opacity: 0.6, pointerEvents: 'none' }}>Lleno</span>
                ) : (
                  <Link to="/login" className="btn btn-primary btn-full">Iniciar sesión</Link>
                )}
              </div>
            );
          })}
        </div>
      )}

      {!usuario && (
        <p className="muted" style={{ marginTop: 'var(--space-4)' }}>Inicia sesión para poder reservar vuelos.</p>
      )}
    </div>
  );
}
