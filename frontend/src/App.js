import { useState } from 'react';
import { Routes, Route } from 'react-router-dom';
import Navbar from './components/Navbar';
import Login from './components/Login';
import Vuelos from './components/Vuelos';
import Reservas from './components/Reservas';
import Notificaciones from './components/Notificaciones';

function RegistroPlaceholder() {
  return <div><h2>Registro</h2><p>Próximamente</p></div>;
}

export default function App() {
  const [menuAbierto, setMenuAbierto] = useState(false);

  return (
    <div className="app">
      <Navbar />
      <div className="content">
        <Routes>
          <Route path="/" element={<Vuelos />} />
          <Route path="/login" element={<Login />} />
          <Route path="/registro" element={<RegistroPlaceholder />} />
          <Route path="/reservas" element={<Reservas />} />
          <Route path="/notificaciones" element={<Notificaciones />} />
        </Routes>
      </div>
    </div>
  );
}
