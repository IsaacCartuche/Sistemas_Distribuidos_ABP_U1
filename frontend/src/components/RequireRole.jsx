import { useNavigate } from 'react-router-dom';
import { authService } from '../services/api';

/**
 * Ruta protegida por rol: solo renderiza si hay sesion activa y el rol coincide.
 * Si no hay sesion -> redirige a /login.
 * Si hay sesion pero rol distinto -> redirige a / (vista cliente).
 */
export default function RequireRole({ children, role }) {
  const navigate = useNavigate();
  const usuario = authService.getUsuario();

  if (!usuario) {
    navigate('/login');
    return null;
  }

  if (usuario.rol !== role) {
    navigate('/');
    return null;
  }

  return children;
}
