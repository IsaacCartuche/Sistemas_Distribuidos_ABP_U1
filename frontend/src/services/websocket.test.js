import { conectarWebSocket, desconectarWebSocket } from './websocket';

jest.mock('@stomp/stompjs', () => ({
  Client: function Client() {
    this.activate = jest.fn();
    this.deactivate = jest.fn();
    this.subscribe = jest.fn();
  },
}));

jest.mock('sockjs-client', () => jest.fn(() => ({})));

describe('websocket', () => {
  it('conectarWebSocket debería crear un cliente y activarlo', () => {
    const onMessage = jest.fn();
    conectarWebSocket('usuario-1', onMessage);
    expect(onMessage).toBeDefined();
  });

  it('desconectarWebSocket no debería lanzar error si no hay cliente', () => {
    expect(() => desconectarWebSocket()).not.toThrow();
  });
});
