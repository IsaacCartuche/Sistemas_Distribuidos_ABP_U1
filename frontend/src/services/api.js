import axios from 'axios';

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
  setToken: (token) => localStorage.setItem('token', token),
  getToken: () => localStorage.getItem('token'),
  removeToken: () => localStorage.removeItem('token'),
};

export const vuelosService = {
  listar: () => api.get('/api/vuelos'),
  detalle: (id) => api.get(`/api/vuelos/${id}`),
};

export const reservasService = {
  crear: (reserva) => api.post('/api/reservas', reserva),
  listar: () => api.get('/api/reservas'),
  cancelar: (id) => api.delete(`/api/reservas/${id}`),
};

export const pagosService = {
  procesar: (pago) => api.post('/api/pagos', pago),
};

export { WS_BASE };

export default api;
