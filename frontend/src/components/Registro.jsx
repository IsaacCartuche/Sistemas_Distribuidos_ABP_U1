import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { authService } from '../services/api';

export default function Registro() {
  const [formData, setFormData] = useState({
    email: '',
    password: '',
    nombre: '',
    apellido: '',
  });
  const [error, setError] = useState('');
  const [enviando, setEnviando] = useState(false);
  const navigate = useNavigate();

  const handleChange = (e) => {
    setFormData({ ...formData, [e.target.name]: e.target.value });
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setEnviando(true);
    try {
      const { authService } = await import('../services/api');
      await authService.register({ ...formData, rol: 'ROLE_PASAJERO' });
      navigate('/login');
    } catch (err) {
      setError(err?.response?.data || 'Error al registrar');
    } finally {
      setEnviando(false);
    }
  };

  return (
    <div style={{ padding: 'var(--space-6)', minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <section style={{ position: 'relative', minHeight: '30vh', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', padding: 'var(--space-8) var(--space-4)' }}>
        <div style={{ position: 'relative', zIndex: 1, textAlign: 'center', maxWidth: '520px' }}>
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--space-2)', padding: 'var(--space-1) var(--space-3)', background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)', borderRadius: 'var(--radius-full)', fontSize: 'var(--text-xs)', fontWeight: 'var(--fw-semibold)', color: '#065f46', marginBottom: 'var(--space-4)' }}>
            🎉 Únete a la comunidad
          </div>
          <h1 style={{ fontSize: 'var(--text-4xl)', fontWeight: 'var(--fw-bold)', lineHeight: '1.1', letterSpacing: '-0.03em', color: 'var(--color-ink)', marginBottom: 'var(--space-3)' }}>Crea tu cuenta</h1>
          <p style={{ fontSize: 'var(--text-lg)', color: 'var(--color-text-secondary)', lineHeight: '1.6' }}>Completa tus datos para reservar vuelos, elegir asientos y gestionar tus viajes en un solo lugar.</p>
        </div>
      </section>

      <div style={{ maxWidth: '460px', margin: '0 auto var(--space-12)' }}>
        {error && <p className="error">{error}</p>}
        <form className="auth-card fade-in" onSubmit={handleSubmit} style={{ background: 'var(--color-surface)', border: 'var(--card-border)', borderRadius: 'var(--radius-xl)', padding: 'var(--space-6)', boxShadow: 'var(--shadow-sm)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-8)', position: 'relative' }}>
            <div style={{ position: 'relative', zIndex: 1 }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '50%', background: 'var(--color-surface)', border: '2px solid var(--color-primary)', color: 'var(--color-primary)', fontSize: 'var(--text-xs)', fontWeight: 'var(--fw-bold)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>1</div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: 'var(--text-xs)', color: 'var(--color-text-secondary)', textTransform: 'uppercase', letterSpacing: '0.08em', marginTop: 'var(--space-2)' }}>Tus datos</div>
              </div>
            </div>
            <div style={{ display: 'flex', gap: 'var(--space-3)' }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '50%', background: 'var(--color-surface)', border: '2px solid var(--color-border)', color: 'var(--color-text-muted)', fontSize: 'var(--text-xs)', fontWeight: 'var(--fw-bold)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>2</div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: 'var(--text-xs)', color: 'var(--color-text-secondary)', textTransform: 'uppercase', letterSpacing: '0.08em', marginTop: 'var(--space-2)' }}>Verificación</div>
              </div>
            </div>
          </div>

          <div className="input-group">
            <label>Nombre</label>
            <input id="reg-nombre" name="nombre" type="text" placeholder="Juan" value={formData.nombre} onChange={handleChange} required className="input" />
            <div style={{ fontSize: 'var(--text-xs)', color: 'var(--color-text-muted)', marginTop: 'var(--space-1)' }}>Nombre tal como aparece en tu identificación</div>
          </div>

          <div className="input-group">
            <label>Apellido</label>
            <input id="reg-apellido" name="apellido" type="text" placeholder="Pérez" value={formData.apellido} onChange={handleChange} required className="input" />
          </div>

          <div className="input-group">
            <label>Correo electrónico</label>
            <input id="reg-email" name="email" type="email" placeholder="tú@correo.com" value={formData.email} onChange={handleChange} required className="input" />
          </div>

          <div className="input-group">
            <label>Contraseña</label>
            <input id="reg-password" name="password" type="password" placeholder="••••••••" value={formData.password} onChange={handleChange} required minLength="6" className="input" />
            <div style={{ fontSize: 'var(--text-xs)', color: 'var(--color-text-muted)', marginTop: 'var(--space-1)' }}>Mínimo 6 caracteres</div>
          </div>

          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 'var(--space-2)', fontSize: 'var(--text-sm)', color: 'var(--color-text-secondary)', marginBottom: 'var(--space-4)' }}>
            <input type="checkbox" id="reg-terms" required />
            <label htmlFor="reg-terms" style={{ margin: '2px 0 0 0', cursor: 'pointer' }}>
              Acepto los <a href="#" style={{ color: 'var(--color-primary)', textDecoration: 'underline' }}>términos y condiciones</a> y la <a href="#" style={{ color: 'var(--color-primary)', textDecoration: 'underline' }}>política de privacidad</a>
            </label>
          </div>

          <button type="submit" disabled={enviando} className="btn btn-primary btn-lg btn-full">
            {enviando ? 'Creando cuenta...' : 'Crear cuenta'}
          </button>
        </form>

        <div style={{ marginTop: 'var(--space-6)', textAlign: 'center', fontSize: 'var(--text-sm)', color: 'var(--color-text-secondary)' }}>
          ¿Ya tienes cuenta?{' '}
          <Link to="/login" style={{ color: 'var(--color-primary)', fontWeight: 'var(--fw-semibold)' }}>Inicia sesión</Link>
        </div>
      </div>
    </div>
  );
}
