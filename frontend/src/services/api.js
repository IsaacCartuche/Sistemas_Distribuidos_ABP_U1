import axios from 'axios';

// En el navegador el front llama al gateway y al websocket-server por los
// puertos publicados en el host. REACT_APP_* se inyectan en build.
const API_BASE = process.env.REACT_APP_API_URL || 'http://localhost:8080';
const WS_BASE = process.env.REACT_APP_WS_URL || 'http://localhost:8090';

const api = axios.create({
  baseURL: API_BASE,
  headers: {
    'Content-Type': 'application/json',
  },
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export const authService = {
  login: (credentials) => api.post('/api/auth/login', credentials),
  register: (userData) => api.post('/api/auth/register', userData),
  perfil: () => api.get('/api/usuarios/perfil'),
  listar: () => api.get('/api/usuarios'),
  setToken: (token) => localStorage.setItem('token', token),
  getToken: () => localStorage.getItem('token'),
  removeToken: () => localStorage.removeItem('token'),
  setUsuario: (usuario) =>
    localStorage.setItem('usuario', JSON.stringify(usuario)),
  getUsuario: () => {
    try {
      return JSON.parse(localStorage.getItem('usuario') || 'null');
    } catch (e) {
      return null;
    }
  },
  removeUsuario: () => localStorage.removeItem('usuario'),
};

// Alias para el panel de administrador (mismo objeto, nombre mas explicito).
export const usuariosService = authService;

export const vuelosService = {
  listar: (params) => api.get('/api/vuelos', { params }),
  detalle: (id) => api.get(`/api/vuelos/${id}`),
  crear: (vuelo) => api.post('/api/vuelos', vuelo),
};

export const inventarioService = {
  asientos: (vueloId, disponibles = true) =>
    api.get(`/api/inventario/vuelos/${vueloId}/asientos`, {
      params: { disponibles },
    }),
};

export const reservasService = {
  // CrearReservaRequest: vueloId, usuarioId, asiento, total, idempotencyKey.
  crear: (reserva) => api.post('/api/reservas', reserva),
  obtener: (id) => api.get(`/api/reservas/${id}`),
};

export const pagosService = {
  // ProcesarPagoRequest: reservaId, monto, metodoPago, idempotencyKey.
  procesar: (pago) => api.post('/api/pagos', pago),
  porReserva: (reservaId) => api.get(`/api/pagos/reserva/${reservaId}`),
};

export const notificacionesService = {
  listar: (usuarioId) =>
    api.get(`/api/notificaciones/usuario/${usuarioId}`),
  marcarLeida: (id, usuarioId) =>
    api.patch(`/api/notificaciones/${id}/leida`, null, {
      params: { usuarioId },
    }),
};

export { API_BASE, WS_BASE };
export default api;
