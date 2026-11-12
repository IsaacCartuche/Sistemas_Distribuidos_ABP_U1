import { useState, useEffect } from 'react';
import { Routes, Route, useNavigate } from 'react-router-dom';
import Navbar from './components/Navbar';
import Login from './components/Login';
import Registro from './components/Registro';
import Vuelos from './components/Vuelos';
import Reservas from './components/Reservas';
import Notificaciones from './components/Notificaciones';
import AdminPanel from './components/AdminPanel';
import RequireRole from './components/RequireRole';
import { authService } from './services/api';

/**
 * Componente de ruta protegida: solo renderiza hijos si hay sesion activa.
 * Si no hay usuario, redirige a /login.
 */
function RequireAuth({ children }) {
  const [verificado, setVerificado] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    const usuario = authService.getUsuario();
    if (!usuario) {
      navigate('/login');
    } else {
      setVerificado(true);
    }
  }, [navigate]);

  if (!verificado) return null;
  return children;
}

export default function App() {
  return (
    <div className="app">
      <Navbar />
      <main className="content">
        <Routes>
          <Route path="/" element={<Vuelos />} />
          <Route path="/login" element={<Login />} />
          <Route path="/registro" element={<Registro />} />
          <Route
            path="/reservas"
            element={
              <RequireAuth>
                <Reservas />
              </RequireAuth>
            }
          />
          <Route
            path="/notificaciones"
            element={
              <RequireAuth>
                <Notificaciones />
              </RequireAuth>
            }
          />
          <Route
            path="/admin"
            element={
              <RequireRole role="ROLE_ADMIN">
                <AdminPanel />
              </RequireRole>
            }
          />
          <Route path="*" element={<Vuelos />} />
        </Routes>
      </main>
    </div>
  );
}
