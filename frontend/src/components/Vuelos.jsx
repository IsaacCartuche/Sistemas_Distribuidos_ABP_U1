import { useState } from 'react';
import { vuelosService } from '../services/api';

export default function Vuelos() {
  const [vuelos, setVuelos] = useState([]);
  const [cargando, setCargando] = useState(true);

  useEffect(() => {
    vuelosService.listar()
      .then((res) => setVuelos(res.data))
      .catch(() => setVuelos([]))
      .finally(() => setCargando(false));
  }, []);

  if (cargando) return <p>Cargando vuelos...</p>;

  return (
    <div>
      <h2>Vuelos disponibles</h2>
      <table>
        <thead>
          <tr>
            <th>Origen</th>
            <th>Destino</th>
            <th>Fecha</th>
            <th>Precio</th>
            <th>Asientos</th>
          </tr>
        </thead>
        <tbody>
          {vuelos.map((v) => (
            <tr key={v.id}>
              <td>{v.origen}</td>
              <td>{v.destino}</td>
              <td>{v.fechaSalida}</td>
              <td>${v.precio}</td>
              <td>{v.capacidadDisponible}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
