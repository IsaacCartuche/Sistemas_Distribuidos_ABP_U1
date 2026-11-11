import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Link } from 'react-router-dom';
import {
  reservasService,
  pagosService,
  authService,
} from '../services/api';

export default function Reservas() {
  const [searchParams] = useSearchParams();
  const [vueloId, setVueloId] = useState(searchParams.get('vuelo') || '');
  const [asiento, setAsiento] = useState('');
  const [reservas, setReservas] = useState([]);
  const [mensaje, setMensaje] = useState('');
  const [cargando, setCargando] = useState(false);
  const [error, setError] = useState('');

  const usuario = authService.getUsuario();

  // Si no hay sesion, redirigir al login.
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

      const reserva = await reservasService.crear({
        vueloId: Number(vueloId),
        usuarioId: usuario.id,
        asiento,
        total: 100,
        idempotencyKey: `res-${Date.now()}`,
      });

      await pagosService.procesar({
        reservaId: reserva.id,
        monto: 100,
        metodoPago: 'tarjeta',
        idempotencyKey: `pago-${Date.now()}`,
      });

      setMensaje('Reserva creada y pago procesado');
      setReservas((prev) => [reserva, ...prev]);
      setVueloId('');
      setAsiento('');
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
      const res = await reservasService.obtener(usuario.id);
      if (res.data) setReservas(Array.isArray(res.data) ? res.data : [res.data]);
    } catch (e) {
      /* No bloquear el flujo si falla la consulta de reservas */
    }
  };

  useEffect(() => {
    cargarMisReservas();
  }, [usuario]);

  return (
    <div>
      <h2>Nueva reserva</h2>
      {!usuario && (
        <p className="error">Debes iniciar sesion para reservar.</p>
      )}
      {error && <p className="error">{error}</p>}
      {usuario && (
        <form onSubmit={handleReservar}>
          <label>ID Vuelo</label>
          <input
            id="vueloId"
            type="number"
            value={vueloId}
            onChange={(e) => setVueloId(e.target.value)}
            required
            min="1"
          />
          <label>Asiento</label>
          <input
            id="asiento"
            type="text"
            value={asiento}
            onChange={(e) => setAsiento(e.target.value)}
            required
          />
          <button type="submit" disabled={cargando || !usuario}>
            {cargando ? 'Procesando...' : 'Reservar y pagar'}
          </button>
        </form>
      )}
      {mensaje && <p className="ok">{mensaje}</p>}
      <h3>Mis reservas</h3>
      <ul>
        {reservas.length === 0 && <li className="muted">Sin reservas.</li>}
        {reservas.map((r) => (
          <li key={r.id}>
            Vuelo {r.vueloId} - Asiento {r.asiento} - ${r.total || ''}
          </li>
        ))}
      </ul>
      {usuario && (
        <>
          <p className="muted">
            Usuario: {usuario.nombre || usuario.email}
          </p>
          <Link to="/">Volver a vuelos</Link>
        </>
      )}
    </div>
  );
}
