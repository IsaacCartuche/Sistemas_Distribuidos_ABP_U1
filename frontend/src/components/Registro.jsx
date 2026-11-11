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
    <div className="auth-page">
      <div className="card auth-card">
        <h2>Registro</h2>
        {error && <p className="error">{error}</p>}
        <form onSubmit={handleSubmit}>
          <label>Nombre</label>
          <input name="nombre" onChange={handleChange} required />
          <label>Apellido</label>
          <input name="apellido" onChange={handleChange} required />
          <label>Email</label>
          <input name="email" type="email" onChange={handleChange} required />
          <label>Contrasena</label>
          <input name="password" type="password" onChange={handleChange} required />
          <button type="submit" disabled={enviando}>
            {enviando ? 'Registrando...' : 'Registrarse'}
          </button>
        </form>
        <p className="muted">
          Ya tienes cuenta? <Link to="/login">Inicia sesion</Link>
        </p>
      </div>
    </div>
  );
}
