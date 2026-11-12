import { useState, useEffect } from 'react';
import { useSearchParams, Link, useNavigate } from 'react-router-dom';
import {
  reservasService,
  pagosService,
  authService,
} from '../services/api';

export default function Reservas() {
  const [searchParams] = useSearchParams();
  const vueloId = searchParams.get('vuelo') || '';
  const asiento = searchParams.get('asiento') || '';
  const [reservas, setReservas] = useState([]);
  const [mensaje, setMensaje] = useState('');
  const [cargando, setCargando] = useState(false);
  const [error, setError] = useState('');
  const navigate = useNavigate();

  const usuario = authService.getUsuario();

  useEffect(() => {
    if (!usuario) {
      window.location.href = '/login';
    }
  }, [usuario]);

  const handleReservar = async (e) => {
    e.preventDefault();
    setMensaje('');
    setError('');
    setCargando(true);
    try {
      if (!usuario) throw new Error('Usuario no autenticado');

      const response = await reservasService.crear({
        vueloId: Number(vueloId),
        usuarioId: usuario.id,
        asiento,
        total: 100,
        idempotencyKey: `res-${Date.now()}`,
      });
      const reserva = response.data;

      if (!reserva || reserva.id == null) {
        throw new Error('La reserva no se creó correctamente: no se recibió un ID válido del servidor');
      }

      await pagosService.procesar({
        reservaId: reserva.id,
        monto: 100,
        metodoPago: 'tarjeta',
        idempotencyKey: `pago-${Date.now()}`,
      });

      setMensaje('Reserva creada y pago procesado');
      setReservas((prev) => [reserva, ...prev]);

      window.dispatchEvent(new Event('reserva-creada'));
      navigate('/');
    } catch (err) {
      const msg =
        err?.response?.data?.message ||
        err?.response?.data ||
        err.message ||
        'Error al reservar';
      setError(typeof msg === 'string' ? msg : 'Error al reservar');
    } finally {
      setCargando(false);
    }
  };

  const cargarMisReservas = async () => {
    if (!usuario) return;
    try {
      const res = await reservasService.obtenerPorUsuario(usuario.id);
      if (res.data) setReservas(Array.isArray(res.data) ? res.data : [res.data]);
    } catch (e) {
      setError('No se pudieron cargar tus reservas');
      console.error(e);
    }
  };

  useEffect(() => {
    cargarMisReservas();
  }, [usuario]);

  return (
    <div style={{ padding: 'var(--space-6)', maxWidth: '720px', margin: '0 auto' }}>
      {!usuario && (
        <div className="card" style={{ textAlign: 'center', padding: 'var(--space-12)' }}>
          <p className="error">Debes iniciar sesión para reservar.</p>
          <Link to="/login" className="btn btn-primary btn-lg" style={{ marginTop: 'var(--space-4)', display: 'inline-flex' }}>Iniciar sesión</Link>
        </div>
      )}
      {usuario && (
        <>
          <h2 style={{ fontSize: 'var(--text-2xl)', fontWeight: 'var(--fw-semibold)', color: 'var(--color-ink)', marginBottom: 'var(--space-4)' }}>Nueva reserva</h2>
          {error && <p className="error">{error}</p>}
          {mensaje && <p className="ok">{mensaje}</p>}
          <form onSubmit={handleReservar} className="card fade-in" style={{ marginBottom: 'var(--space-8)' }}>
            <input type="hidden" name="vueloId" value={vueloId} />
            <input type="hidden" name="asiento" value={asiento} />
            <div className="reserva-summary">
              <div className="summary-item">
                <span className="label">Vuelo</span>
                <span className="value">#{vueloId}</span>
              </div>
              <div className="summary-item">
                <span className="label">Asiento</span>
                <span className="value">{asiento}</span>
              </div>
              <div className="summary-item">
                <span className="label">Total</span>
                <span className="value">$100</span>
              </div>
            </div>
            <button
              type="submit"
              disabled={cargando || !usuario}
              className="btn btn-primary btn-lg btn-full"
            >
              {cargando ? 'Procesando...' : 'Reservar y pagar'}
            </button>
          </form>

          <h3 style={{ fontSize: 'var(--text-lg)', fontWeight: 'var(--fw-semibold)', color: 'var(--color-ink)', marginBottom: 'var(--space-3)' }}>Mis reservas</h3>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: 'var(--space-3)' }}>
            {reservas.length === 0 && (
              <div className="muted" style={{ textAlign: 'center', padding: 'var(--space-4)' }}>Sin reservas.</div>
            )}
            {reservas.map((r) => (
              <div key={r.id} className="card fade-in" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: 'var(--space-4)' }}>
                <div>
                  <div style={{ fontWeight: 'var(--fw-semibold)', color: 'var(--color-ink)' }}>Vuelo {r.vueloId}</div>
                  <div style={{ fontSize: 'var(--text-sm)', color: 'var(--color-text-muted)' }}>Asiento {r.asiento}</div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)' }}>
                  <span className="badge success">Confirmada</span>
                  <span style={{ fontSize: 'var(--text-sm)', fontWeight: 'var(--fw-bold)', color: 'var(--color-ink)' }}>${r.total || ''}</span>
                </div>
              </div>
            ))}
          </div>
          {usuario && (
            <>
              <p className="muted" style={{ marginTop: 'var(--space-6)' }}>
                Usuario: {usuario.nombre || usuario.email}
              </p>
              <Link to="/" className="btn btn-ghost" style={{ marginTop: 'var(--space-2)', display: 'inline-flex' }}>← Volver a vuelos</Link>
            </>
          )}
        </>
      )}
    </div>
  );
}
