import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { authService } from '../services/api';
import { conectarWebSocket, desconectarWebSocket } from '../services/websocket';

function Navbar() {
  const [usuario, setUsuario] = useState(null);
  const [notificaciones, setNotificaciones] = useState([]);

  const token = authService.getToken();

  useEffect(() => {
    if (token) {
      const usuarioGuardado = localStorage.getItem('usuario');
      if (usuarioGuardado) {
        try {
          setUsuario(JSON.parse(usuarioGuardado));
        } catch (e) {
          setUsuario(null);
        }
      }
    }
  }, [token]);

  useEffect(() => {
    if (usuario && usuario.id) {
      conectarWebSocket(usuario.id, (msg) => {
        setNotificaciones((prev) => [...prev, msg]);
      });
    }
    return () => {
      desconectarWebSocket();
    };
  }, [usuario]);

  const handleLogout = () => {
    authService.removeToken();
    localStorage.removeItem('usuario');
    desconectarWebSocket();
    setUsuario(null);
    window.location.reload();
  };

  if (!usuario) {
    return (
      <nav className="navbar">
        <Link to="/">Reservas de Vuelos</Link>
        <div className="navbar-actions">
          <Link to="/login">Iniciar sesión</Link>
          <Link to="/registro">Registrarse</Link>
        </div>
      </nav>
    );
  }

  return (
    <nav className="navbar">
      <Link to="/">Reservas de Vuelos</Link>
      <div className="navbar-actions">
        <span>Hola, {usuario.nombre || 'Usuario'}</span>
        <span className="badge">{notificaciones.length} nuevas</span>
        <button onClick={handleLogout}>Cerrar sesión</button>
      </div>
    </nav>
  );
}

export default Navbar;
