import { useState, useEffect } from 'react';
import { conectarWebSocket, desconectarWebSocket, leerNotificacionesLocal } from '../services/websocket';
import { authService } from '../services/api';

export default function Notificaciones() {
  const [items, setItems] = useState(() => leerNotificacionesLocal());
  const [cargando, setCargando] = useState(false);

  const usuario = authService.getUsuario();

  useEffect(() => {
    if (usuario && usuario.id) {
      conectarWebSocket(usuario.id, (nueva) => {
        setItems((prev) => [nueva, ...prev]);
      });
    }
    return () => {
      desconectarWebSocket();
    };
  }, [usuario]);

  const marcarLeida = async (id) => {
    if (!usuario) return;
    setCargando(true);
    try {
      // El backend expone PATCH /api/notificaciones/{id}/leida?usuarioId=...
      const resp = await fetch(
        `${process.env.REACT_APP_API_URL || 'http://localhost:8080'}/api/notificaciones/${id}/leida?usuarioId=${usuario.id}`,
        { method: 'PATCH' }
      );
      if (!resp.ok) throw new Error('No se pudo marcar como leída');
      setItems((prev) => prev.filter((n) => n.id !== id));
    } catch (e) {
      /* silencioso: la lista local sigue siendo la fuente de verdad */
    } finally {
      setCargando(false);
    }
  };

  return (
    <div>
      <h2>Notificaciones</h2>
      {cargando && <p className="muted">Sincronizando...</p>}
      <ul>
        {items.length === 0 && (
          <li className="muted">Sin notificaciones aún.</li>
        )}
        {items.map((n) => (
          <li key={n.id || n.mensaje}>
            <strong>{n.tipo || 'WS'}</strong>: {n.mensaje || n.mensajeTexto || JSON.stringify(n)}
            <button
              onClick={() => marcarLeida(n.id)}
              className="link-button"
              disabled={cargando}
            >
              Leída
            </button>
          </li>
        ))}
      </ul>
      {usuario && (
        <p className="muted">
          Usuario: {usuario.nombre || usuario.email}
        </p>
      )}
    </div>
  );
}
