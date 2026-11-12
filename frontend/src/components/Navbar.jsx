import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authService } from '../services/api';
import {
  conectarWebSocket,
  desconectarWebSocket,
} from '../services/websocket';

function Navbar() {
  const [usuario, setUsuario] = useState(() => authService.getUsuario());
  const [notificaciones, setNotificaciones] = useState(0);
  const navigate = useNavigate();

  useEffect(() => {
    const onAuthChange = () => setUsuario(authService.getUsuario());
    window.addEventListener('auth-change', onAuthChange);
    return () => window.removeEventListener('auth-change', onAuthChange);
  }, []);

  useEffect(() => {
    if (usuario && usuario.id) {
      conectarWebSocket(usuario.id, () =>
        setNotificaciones((prev) => prev + 1)
      );
    }
    return () => {
      desconectarWebSocket();
    };
  }, [usuario]);

  const handleLogout = () => {
    authService.removeToken();
    authService.removeUsuario();
    desconectarWebSocket();
    setUsuario(null);
    setNotificaciones(0);
    window.dispatchEvent(new Event('auth-change'));
    navigate('/login');
  };

  return (
    <nav className="navbar">
      <Link to="/" className="nav-brand">
        Reserva<span style={{ color: 'var(--color-accent)' }}>Vuela</span>
      </Link>

      <div className="navbar-actions">
        {usuario ? (
          <>
            <Link to="/reservas">Mis reservas</Link>
            <Link to="/notificaciones">
              Notificaciones
              {notificaciones > 0 && (
                <span className="badge success">{notificaciones}</span>
              )}
            </Link>
            <span className="muted">
              Hola, {usuario.nombre || usuario.email || 'Usuario'}
            </span>
            {usuario.rol === 'ROLE_ADMIN' && (
              <Link to="/admin">Panel de Administrador</Link>
            )}
            <button className="btn btn-sm btn-ghost" onClick={handleLogout}>
              Cerrar sesión
            </button>
          </>
        ) : (
          <>
            <Link to="/login">Iniciar sesión</Link>
            <Link to="/registro" className="btn btn-primary btn-sm">
              Registrarse
            </Link>
          </>
        )}
      </div>
    </nav>
  );
}

export default Navbar;
