import { useState, useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { vuelosService } from '../services/api';
import { authService } from '../services/api';

export default function Vuelos() {
  const [vuelos, setVuelos] = useState([]);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState('');
  const [searchParams] = useSearchParams();
  const usuario = authService.getUsuario();

  useEffect(() => {
    vuelosService
      .listar()
      .then((res) => setVuelos(res.data || []))
      .catch((err) =>
        setError(
          err?.response?.status
            ? `No se pudo consultar el servicio de vuelos (${err.response.status})`
            : 'No se pudo consultar el servicio de vuelos'
        )
      )
      .finally(() => setCargando(false));
  }, []);

  if (cargando) return <p className="muted">Cargando vuelos...</p>;

  return (
    <div>
      <h2>Vuelos disponibles</h2>
      {error && <p className="error">{error}</p>}
      {!error && vuelos.length === 0 && (
        <p className="muted">No hay vuelos disponibles.</p>
      )}
      {vuelos.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>Origen</th>
              <th>Destino</th>
              <th>Fecha</th>
              <th>Hora</th>
              <th>Precio</th>
              <th>Asientos</th>
              <th>Estado</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {vuelos.map((v) => {
              const disponibles = Number(v.capacidadDisponible || 0);
              const total = Number(v.capacidadTotal || 0);
              const lleno = disponibles <= 0;
              return (
                <tr key={v.id}>
                  <td>{v.id}</td>
                  <td>{v.origen}</td>
                  <td>{v.destino}</td>
                  <td>{v.fechaSalida}</td>
                  <td>{v.horaSalida}</td>
                  <td>${v.precio}</td>
                  <td>
                    {disponibles} de {total}
                  </td>
                  <td>
                    <span className={lleno ? 'estado-lleno' : 'estado-disponible'}>
                      {lleno ? 'Lleno' : 'Disponible'}
                    </span>
                  </td>
                  <td>
                    {usuario && !lleno ? (
                      <Link
                        to={`/reservas?vuelo=${v.id}`}
                        className="btn-reservar"
                      >
                        Reservar
                      </Link>
                    ) : lleno ? (
                      <span className="btn-reservar disabled">Lleno</span>
                    ) : (
                      <Link to="/login" className="btn-reservar">
                        Reservar (inicia sesion)
                      </Link>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {!usuario && (
        <p className="muted">
          Inicia sesion para poder reservar vuelos.
        </p>
      )}
    </div>
  );
}
