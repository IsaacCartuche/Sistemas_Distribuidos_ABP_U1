import { render, screen } from '@testing-library/react';
import Notificaciones from './components/Notificaciones';

describe('Notificaciones', () => {
  it('muestra mensaje cuando no hay notificaciones', () => {
    localStorage.clear();
    render(<Notificaciones />);
    expect(screen.getByText(/Sin notificaciones aún/)).toBeInTheDocument();
  });

  it('muestra lista de notificaciones guardadas', () => {
    localStorage.setItem(
      'notificaciones_ws',
      JSON.stringify([{ tipo: 'RESERVA_CREADA', mensaje: 'Reserva confirmada' }])
    );
    render(<Notificaciones />);
    expect(screen.getByText(/Reserva confirmada/)).toBeInTheDocument();
  });
});
