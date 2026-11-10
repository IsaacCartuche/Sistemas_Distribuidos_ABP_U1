# Frontend

Interfaz de usuario para búsqueda de vuelos, selección de asientos, reservas y pagos. Consume la API a través del API Gateway y recibe notificaciones en tiempo real vía WebSocket.

## Tecnología
- React 18 + Create React App
- Axios (HTTP)
- SockJS + STOMP (WebSocket)
- React Router (navegación)

## Puerto
3000

## Estructura
```
src/
  index.js              # Punto de entrada
  App.js                # Rutas y layout principal
  App.css               # Estilos base
  services/
    api.js              # Axios con interceptors JWT
    websocket.js        # Conexión STOMP/SockJS al WebSocket Server
  components/
    Navbar.jsx          # Barra de navegación con autenticación y notificaciones
    Login.jsx           # Inicio de sesión
    Vuelos.jsx          # Catálogo de vuelos
    Reservas.jsx        # Creación de reservas y pagos
    Notificaciones.jsx  # Campana de notificaciones
```

## Variables de entorno
- `REACT_APP_API_URL` — URL del API Gateway (por defecto `http://localhost:8080`)

## WebSocket
- Conecta a `ws://localhost:8090/notificaciones` (SockJS)
- Se suscribe a `/user/{usuarioId}/topic/notificaciones`

## Construir y ejecutar localmente
```bash
npm install
npm start
```

## Construir para Docker
```bash
docker build -t frontend-reservas .
docker run -p 3000:3000 frontend-reservas
```

## Pruebas
```bash
npm test
```