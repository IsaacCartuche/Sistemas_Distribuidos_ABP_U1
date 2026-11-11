import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Link } from 'react-router-dom';

export default function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [enviando, setEnviando] = useState(false);
  const [mostrarPassword, setMostrarPassword] = useState(false);
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setEnviando(true);
    try {
      const { authService } = await import('../services/api');
      const res = await authService.login({ email, password });
      // El backend responde { token, id, email, rol }.
      authService.setToken(res.data.token);
      authService.setUsuario({
        id: res.data.id,
        email: res.data.email,
        rol: res.data.rol,
        nombre: res.data.nombre || res.data.email,
      });
      // Notificar a la app (Navbar, etc.) y redirigir segun el rol.
      window.dispatchEvent(new Event('auth-change'));
      if (res.data.rol === 'ROLE_ADMIN') {
        navigate('/admin');
      } else {
        navigate('/');
      }
    } catch (err) {
      const mensaje =
        err?.response?.data?.message ||
        err?.response?.data ||
        'Credenciales invalidas';
      setError(typeof mensaje === 'string' ? mensaje : 'Credenciales invalidas');
    } finally {
      setEnviando(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="card auth-card">
        <h2>Iniciar sesion</h2>
        <p className="muted">Accede con tu cuenta de la plataforma de reservas.</p>

        {error && <p className="error">{error}</p>}

        <form onSubmit={handleSubmit}>
          <label htmlFor="login-email">Email</label>
          <input
            id="login-email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            autoComplete="email"
          />

          <label htmlFor="login-password">Contrasena</label>
          <div className="password-row">
            <input
              id="login-password"
              type={mostrarPassword ? 'text' : 'password'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              autoComplete="current-password"
            />
            <button
              type="button"
              className="link-button"
              onClick={() => setMostrarPassword((v) => !v)}
            >
              {mostrarPassword ? 'Ocultar' : 'Ver'}
            </button>
          </div>

          <button type="submit" disabled={enviando}>
            {enviando ? 'Entrando...' : 'Entrar'}
          </button>
        </form>

        <p className="muted">
          No tienes cuenta? <Link to="/registro">Registrate</Link>
        </p>
      </div>
    </div>
  );
}
