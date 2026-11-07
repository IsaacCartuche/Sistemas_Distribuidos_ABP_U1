import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { WS_BASE } from './api';

let client = null;
let onMessageCallback = null;

export const conectarWebSocket = (usuarioId, onMessage) => {
  onMessageCallback = onMessage;

  const socket = new SockJS(`${WS_BASE}/notificaciones`);
  client = new Client({
    webSocketFactory: () => socket,
    reconnectDelay: 5000,
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
    onConnect: () => {
      client.subscribe(`/user/${usuarioId}/topic/notificaciones`, (message) => {
        if (onMessageCallback) {
          try {
            const payload = JSON.parse(message.body);
            onMessageCallback(payload);
          } catch (e) {
            onMessageCallback(message.body);
          }
        }
      });
    },
    onStompError: (frame) => {
      console.error('STOMP error:', frame);
    },
  });

  client.activate();
};

export const desconectarWebSocket = () => {
  if (client) {
    client.deactivate();
    client = null;
  }
  onMessageCallback = null;
};

export { client };
