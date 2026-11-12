import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { vuelosService, usuariosService, reservasService } from '../services/api';

/**
 * Panel de administrador.
 * Solo accesible si el usuario tiene rol ROLE_ADMIN (verificado por RequireRole en App.js).
 * Secciones:
 *   - Crear vuelo: formulario para dar de alta un vuelo.
 *   - Gestionar usuarios: listado de usuarios registrados.
 *   - Ver reserva: consulta una reserva por su ID.
 */
export default function AdminPanel() {
  const [pestana, setPestana] = useState('vuelos');

  // --- Crear vuelo ---
  const [vuelo, setVuelo] = useState({
    origen: '',
    destino: '',
    fechaSalida: '',
    horaSalida: '',
    precio: '',
    estado: 'PROGRAMADO',
    capacidadTotal: '',
    capacidadDisponible: '',
  });
  const [msgCrear, setMsgCrear] = useState('');
  const [errorCrear, setErrorCrear] = useState('');

  const handleVueloChange = (e) => {
    setVuelo({ ...vuelo, [e.target.name]: e.target.value });
  };

  const handleCrearVuelo = async (e) => {
    e.preventDefault();
    setMsgCrear(''); setErrorCrear('');
    try {
      await vuelosService.crear({
        origen: vuelo.origen,
        destino: vuelo.destino,
        fechaSalida: vuelo.fechaSalida,
        horaSalida: vuelo.horaSalida,
        precio: Number(vuelo.precio),
        estado: vuelo.estado,
        capacidadTotal: Number(vuelo.capacidadTotal),
        capacidadDisponible: Number(vuelo.capacidadDisponible),
      });
      setMsgCrear('Vuelo creado exitosamente.');
      setVuelo({ origen: '', destino: '', fechaSalida: '', horaSalida: '', precio: '', estado: 'PROGRAMADO', capacidadTotal: '', capacidadDisponible: '' });
    } catch (err) {
      setErrorCrear(err?.response?.data?.message || 'Error al crear vuelo');
    }
  };

  // --- Gestionar usuarios ---
  const [usuarios, setUsuarios] = useState([]);
  const [cargandoUsuarios, setCargandoUsuarios] = useState(false);
  const [errorUsuarios, setErrorUsuarios] = useState('');

  useEffect(() => {
    if (pestana === 'usuarios') cargarUsuarios();
  }, [pestana]);

  const cargarUsuarios = async () => {
    setCargandoUsuarios(true);
    setErrorUsuarios('');
    try {
      const res = await usuariosService.listar();
      setUsuarios(res.data || []);
    } catch (err) {
      setErrorUsuarios('No se pudo cargar el listado de usuarios');
    } finally {
      setCargandoUsuarios(false);
    }
  };

  // --- Ver reserva por ID ---
  const [idReserva, setIdReserva] = useState('');
  const [reserva, setReserva] = useState(null);
  const [errorReserva, setErrorReserva] = useState('');

  const handleConsultarReserva = async () => {
    setErrorReserva(''); setReserva(null);
    try {
      const res = await reservasService.obtener(Number(idReserva));
      setReserva(res.data || null);
    } catch (err) {
      setErrorReserva('No se pudo encontrar la reserva');
    }
  };

  return (
    <div className="admin-panel">
      <h1>Panel de Administrador</h1>
      <p className="muted">Bienvenido, {usuarioNombre()}</p>

      <nav className="admin-tabs">
        <button
          className={pestana === 'vuelos' ? 'activo' : ''}
          onClick={() => setPestana('vuelos')}
        >
          Crear Vuelo
        </button>
        <button
          className={pestana === 'usuarios' ? 'activo' : ''}
          onClick={() => setPestana('usuarios')}
        >
          Gestionar Usuarios
        </button>
        <button
          className={pestana === 'reservas' ? 'activo' : ''}
          onClick={() => setPestana('reservas')}
        >
          Ver Reserva
        </button>
        <Link to="/" className="btn-salir">Volver a vuelos</Link>
      </nav>

      {pestana === 'vuelos' && (
        <section className="admin-seccion">
          <h2>Crear Vuelo</h2>
          {errorCrear && <p className="error">{errorCrear}</p>}
          {msgCrear && <p className="exito">{msgCrear}</p>}
          <form onSubmit={handleCrearVuelo}>
            <label>Origen</label>
            <input name="origen" value={vuelo.origen} onChange={handleVueloChange} required />
            <label>Destino</label>
            <input name="destino" value={vuelo.destino} onChange={handleVueloChange} required />
            <label>Fecha salida</label>
            <input name="fechaSalida" type="date" value={vuelo.fechaSalida} onChange={handleVueloChange} required />
            <label>Hora salida</label>
            <input name="horaSalida" type="time" value={vuelo.horaSalida} onChange={handleVueloChange} required />
            <label>Precio</label>
            <input name="precio" type="number" step="0.01" value={vuelo.precio} onChange={handleVueloChange} required />
            <label>Estado</label>
            <select name="estado" value={vuelo.estado} onChange={handleVueloChange}>
              <option value="ACTIVO">Activo</option>
              <option value="INACTIVO">Inactivo</option>
              <option value="CANCELADO">Cancelado</option>
            </select>
            <label>Capacidad total</label>
            <input name="capacidadTotal" type="number" value={vuelo.capacidadTotal} onChange={handleVueloChange} required />
            <label>Capacidad disponible</label>
            <input name="capacidadDisponible" type="number" value={vuelo.capacidadDisponible} onChange={handleVueloChange} required />
            <button type="submit">Crear vuelo</button>
          </form>
        </section>
      )}

      {pestana === 'usuarios' && (
        <section className="admin-seccion">
          <h2>Gestionar Usuarios</h2>
          {errorUsuarios && <p className="error">{errorUsuarios}</p>}
          {cargandoUsuarios && <p className="muted">Cargando usuarios...</p>}
          {!cargandoUsuarios && !errorUsuarios && usuarios.length === 0 && (
            <p className="muted">No hay usuarios registrados.</p>
          )}
          {!cargandoUsuarios && !errorUsuarios && usuarios.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Nombre</th>
                  <th>Email</th>
                  <th>Rol</th>
                </tr>
              </thead>
              <tbody>
                {usuarios.map((u) => (
                  <tr key={u.id}>
                    <td>{u.id}</td>
                    <td>{u.nombre}</td>
                    <td>{u.email}</td>
                    <td>{u.rol}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}

      {pestana === 'reservas' && (
        <section className="admin-seccion">
          <h2>Ver Reserva</h2>
          {errorReserva && <p className="error">{errorReserva}</p>}
          <form onSubmit={(e) => { e.preventDefault(); handleConsultarReserva(); }}>
            <label>ID de reserva</label>
            <input
              name="idReserva"
              type="number"
              value={idReserva}
              onChange={(e) => setIdReserva(e.target.value)}
              required
            />
            <button type="submit">Consultar</button>
          </form>
          {reserva && (
            <div className="tarjeta-reserva">
              <p><strong>ID:</strong> {reserva.id}</p>
              <p><strong>Usuario ID:</strong> {reserva.usuarioId}</p>
              <p><strong>Vuelo ID:</strong> {reserva.vueloId}</p>
              <p><strong>Asiento:</strong> {reserva.asiento}</p>
              <p><strong>Total:</strong> ${reserva.total}</p>
              <p><strong>Estado:</strong> {reserva.estado}</p>
            </div>
          )}
        </section>
      )}
    </div>
  );
}

function usuarioNombre() {
  try {
    const u = JSON.parse(localStorage.getItem('usuario') || 'null');
    return u ? (u.nombre || u.email || 'Administrador') : 'Administrador';
  } catch (e) {
    return 'Administrador';
  }
}
