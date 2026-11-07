import { useState } from 'react';

export default function Reservas() {
  const [vueloId, setVueloId] = useState('');
  const [asiento, setAsiento] = useState('');
  const [reservas, setReservas] = useState([]);
  const [mensaje, setMensaje] = useState('');

  const handleReservar = async (e) => {
    e.preventDefault();
    setMensaje('');
    try {
      const reserva = await reservasService.crear({ vueloId, asiento });
      await pagosService.procesar({ reservaId: reserva.id, monto: 100 });
      setMensaje('Reserva creada y pago procesado');
      setReservas((prev) => [...prev, reserva]);
      setVueloId('');
      setAsiento('');
    } catch (err) {
      setMensaje('Error al reservar: ' + err.message);
    }
  };

  return (
    <div>
      <h2>Nueva reserva</h2>
      <form onSubmit={handleReservar}>
        <label>ID Vuelo</label>
        <input
          id="vueloId"
          type="text"
          value={vueloId}
          onChange={(e) => setVueloId(e.target.value)}
          required
        />
        <label>Asiento</label>
        <input
          id="asiento"
          type="text"
          value={asiento}
          onChange={(e) => setAsiento(e.target.value)}
          required
        />
        <button type="submit">Reservar y pagar</button>
      </form>
      {mensaje && <p>{mensaje}</p>}
      <h3>Mis reservas</h3>
      <ul>
        {reservas.map((r) => (
          <li key={r.id}>Vuelo {r.vueloId} - Asiento {r.asiento}</li>
        ))}
      </ul>
    </div>
  );
}
