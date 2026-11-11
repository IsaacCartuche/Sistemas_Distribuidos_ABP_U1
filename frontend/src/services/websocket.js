import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { WS_BASE } from './api';

const CLAVE_STORAGE = 'notificaciones_ws';

let client = null;
let onMessageCallback = null;

// El websocket-server publica las notificaciones en /topic/usuario/{usuarioId}.
// Se suscribe a ese topic (no a /user/...) porque la sesion STOMP no lleva
// Principal de Spring Security: convertAndSendToUser no resolveria destino.
export const conectarWebSocket = (usuarioId, onMessage) => {
  onMessageCallback = onMessage;

  const socket = new SockJS(`${WS_BASE}/notificaciones`);
  client = new Client({
    webSocketFactory: () => socket,
    reconnectDelay: 5000,
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
    onConnect: () => {
      client.subscribe(`/topic/usuario/${usuarioId}`, (message) => {
        if (!onMessageCallback) return;
        try {
          const payload = JSON.parse(message.body);
          persistir(payload);
          onMessageCallback(payload);
        } catch (e) {
          const texto = { tipo: 'WS', mensaje: message.body };
          persistir(texto);
          onMessageCallback(texto);
        }
      });
    },
    onStompError: (frame) => {
      console.error('STOMP error:', frame);
    },
  });

  client.activate();
};

const persistir = (notificacion) => {
  try {
    const actuales = JSON.parse(localStorage.getItem(CLAVE_STORAGE) || '[]');
    actuales.unshift(notificacion);
    localStorage.setItem(CLAVE_STORAGE, JSON.stringify(actuales.slice(0, 50)));
  } catch (e) {
    /* localStorage no disponible: se ignora la persistencia */
  }
};

export const leerNotificacionesLocal = () => {
  try {
    return JSON.parse(localStorage.getItem(CLAVE_STORAGE) || '[]');
  } catch (e) {
    return [];
  }
};

export const desconectarWebSocket = () => {
  if (client) {
    client.deactivate();
    client = null;
  }
  onMessageCallback = null;
};

export { client };
