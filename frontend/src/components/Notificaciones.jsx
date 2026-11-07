import { useState, useEffect } from 'react';

export default function Notificaciones() {
  const [items, setItems] = useState([]);

  useEffect(() => {
    const guardadas = JSON.parse(localStorage.getItem('notificaciones_ws') || '[]');
    setItems(guardadas);
  }, []);

  return (
    <div>
      <h2>Notificaciones</h2>
      <ul>
        {items.map((n, i) => (
          <li key={i}>
            <strong>{n.tipo}</strong>: {n.mensaje}
          </li>
        ))}
      </ul>
      {items.length === 0 && <p>Sin notificaciones aún.</p>}
    </div>
  );
}
