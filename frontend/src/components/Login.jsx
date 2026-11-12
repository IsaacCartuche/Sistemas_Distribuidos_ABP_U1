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
      authService.setToken(res.data.token);
      authService.setUsuario({
        id: res.data.id,
        email: res.data.email,
        rol: res.data.rol,
        nombre: res.data.nombre || res.data.email,
      });
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
    <div style={{ padding: 'var(--space-6)', minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <section style={{ position: 'relative', minHeight: '35vh', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', padding: 'var(--space-8) var(--space-4)' }}>
        <div style={{ position: 'relative', zIndex: 1, textAlign: 'center', maxWidth: '520px' }}>
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--space-2)', padding: 'var(--space-1) var(--space-3)', background: 'rgba(37,99,235,0.08)', border: '1px solid rgba(37,99,235,0.2)', borderRadius: 'var(--radius-full)', fontSize: 'var(--text-xs)', fontWeight: 'var(--fw-semibold)', color: 'var(--color-primary)', marginBottom: 'var(--space-4)' }}>
            ✈️ Bienvenido de vuelta
          </div>
          <h1 style={{ fontSize: 'var(--text-4xl)', fontWeight: 'var(--fw-bold)', lineHeight: '1.1', letterSpacing: '-0.03em', color: 'var(--color-ink)', marginBottom: 'var(--space-3)' }}>Inicia sesión</h1>
          <p style={{ fontSize: 'var(--text-lg)', color: 'var(--color-text-secondary)', lineHeight: '1.6' }}>Accede a tus reservas, boletos y estado de vuelo desde tu cuenta personal.</p>
        </div>
      </section>

      <div style={{ maxWidth: '460px', margin: '0 auto var(--space-12)' }}>
        {error && <p className="error">{error}</p>}
        <form className="auth-card fade-in" onSubmit={handleSubmit} style={{ background: 'var(--color-surface)', border: 'var(--card-border)', borderRadius: 'var(--radius-xl)', padding: 'var(--space-6)', boxShadow: 'var(--shadow-sm)' }}>
          <div className="input-group">
            <label htmlFor="login-email">Correo electrónico</label>
            <input
              id="login-email"
              type="email"
              placeholder="tú@correo.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoComplete="email"
              className="input"
            />
          </div>
          <div className="input-group" style={{ position: 'relative' }}>
            <label htmlFor="login-password">Contraseña</label>
            <input
              id="login-password"
              type={mostrarPassword ? 'text' : 'password'}
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              autoComplete="current-password"
              className="input"
              style={{ paddingRight: '40px' }}
            />
            <button
              type="button"
              className="btn btn-ghost"
              style={{ position: 'absolute', right: 'var(--space-3)', top: 'calc(var(--space-3) + 1.5rem)', fontSize: 'var(--text-xs)', padding: 'var(--space-1) var(--space-2)' }}
              onClick={() => setMostrarPassword((v) => !v)}
            >
              {mostrarPassword ? 'Ocultar' : 'Ver'}
            </button>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)', fontSize: 'var(--text-sm)', marginBottom: 'var(--space-4)' }}>
            <input type="checkbox" id="recordar" style={{ accentColor: 'var(--color-primary)', width: '16px', height: '16px' }} />
            <label htmlFor="recordar" style={{ margin: 0, cursor: 'pointer' }}>Recordarme</label>
          </div>
          <button type="submit" disabled={enviando} className="btn btn-primary btn-full btn-lg">
            {enviando ? 'Entrando...' : 'Entrar'}
          </button>
        </form>

        <div className="form-footer" style={{ marginTop: 'var(--space-6)', paddingTop: 'var(--space-6)', borderTop: '1px solid var(--color-border)', textAlign: 'center', fontSize: 'var(--text-sm)', color: 'var(--color-text-secondary)' }}>
          ¿No tienes cuenta?{' '}
          <Link to="/registro" style={{ color: 'var(--color-primary)', fontWeight: 'var(--fw-semibold)' }}>Regístrate gratis</Link>
        </div>
      </div>
    </div>
  );
}
