# ws-chat-server

Servidor WebSocket de chat en vivo, hecho con **Spring Boot 3 + Java 17**, instrumentado con observabilidad completa (métricas, trazas y logs).

## Funcionalidades

- Mensajería en tiempo real vía WebSockets nativos (`spring-boot-starter-websocket`)
- Sistema de **join** (registro de username por sesión), **typing** (indicador de escritura) y lista de usuarios conectados
- **Observabilidad instrumentada:**
  - 📊 Métricas (Micrometer → Prometheus → Grafana)
  - 🔍 Trazas distribuidas (Micrometer Tracing/Brave → Zipkin)
  - 📋 Logs estructurados en JSON (Logback → Logstash → Elasticsearch → Kibana)

## Estructura

```
ws-server/
├── pom.xml
├── docker-compose.yml       (stack de observabilidad completo)
├── prometheus.yml
├── logstash.conf
└── src/main/
    ├── java/com/chat/
    │   ├── WsServerApplication.java
    │   ├── WebSocketConfig.java
    │   ├── ChatHandler.java
    │   └── ChatMessage.java
    └── resources/
        ├── application.properties
        └── logback-spring.xml
```

## Requisitos

- Java 17+
- **Maven NO es obligatorio** — el proyecto incluye el Maven Wrapper (`mvnw` / `mvnw.cmd`), que descarga Maven automáticamente la primera vez que lo corres
- Docker (solo si quieres levantar el stack de observabilidad)

## Cómo correrlo

**Windows (PowerShell o CMD):**
```powershell
.\mvnw.cmd spring-boot:run
```

**Mac / Linux:**
```bash
./mvnw spring-boot:run
```

La primera vez tarda un poco más porque descarga Maven solo; las siguientes veces arranca directo. Si prefieres usar tu propio Maven instalado, `mvn spring-boot:run` también funciona igual.

El WebSocket queda listo en `ws://localhost:8080/chat`.

> Necesitas también el cliente (repo `ws-chat-client`) corriendo en `http://localhost:5173` para probar el chat completo — el servidor por sí solo no tiene interfaz.

## Protocolo WebSocket

Los mensajes son JSON con un campo `type`:

| type | Quién lo envía | Campos relevantes |
|---|---|---|
| `join` | Cliente, al conectar | `username` |
| `message` | Cliente → Servidor → todos | `username`, `text` |
| `typing` | Cliente, al escribir (debounced) | `username`, `typing` (bool) |
| `system` | Servidor, en join/leave | `text`, `count`, `users` (lista) |

## Observabilidad: Grafana · Zipkin · Kibana

| Herramienta | Responde a | Puerto |
|---|---|---|
| **Grafana** (+ Prometheus) | ¿Cuántos mensajes/seg? ¿Cuántas sesiones activas? | `:3000` |
| **Zipkin** | ¿Cuánto tardó en procesarse este mensaje? ¿Dónde está la latencia? | `:9411` |
| **Kibana** (+ ELK) | ¿Qué pasó exactamente y cuándo? Búsqueda full-text en logs | `:5601` |

### 1. Levantar el stack

```bash
docker compose up -d
```

Espera ~1 minuto a que Elasticsearch termine de arrancar (es el más lento), y luego corre el servidor con `mvn spring-boot:run`.

### 2. Verificar cada pieza

- **Métricas crudas:** `http://localhost:8080/actuator/prometheus` — deberías ver `ws_messages_received_total`, `ws_messages_broadcast_total`, `ws_sessions_active`.
- **Grafana:** `http://localhost:3000` → login `admin`/`admin` → Connections → Add data source → **Prometheus** → URL `http://prometheus:9090` → Save & Test. Dashboards → New → Add visualization, con queries como:
  - `rate(ws_messages_received_total[1m])` — mensajes/seg
  - `ws_sessions_active` — conexiones en tiempo real
- **Zipkin:** `http://localhost:9411` → Service Name → `ws-chat` → Run Query. Envía mensajes en el chat y búscalos aquí — cada uno es un span (`ws.handle-message`) con tags `chat.username`, `chat.text_length`, `message.type`, etc.
- **Kibana:** `http://localhost:5601` → Stack Management → Index Patterns → Create `ws-chat-logs-*` (timestamp: `@timestamp`) → Analytics → Discover. Filtra por `username`, `active_sessions`, `traceId`, `level`.

### 3. Correlacionar las tres herramientas

Cada log incluye el `traceId` de Micrometer Tracing. Copia ese valor de un log en Kibana y búscalo en Zipkin para ver la traza completa de esa operación exacta — así conectas "qué pasó" (Kibana) con "dónde estuvo lento" (Zipkin) y "cuándo/cuánto" (Grafana).

> Nota: `management.zipkin.tracing.endpoint` en `application.properties` apunta a `localhost:9411` porque el servidor corre fuera de Docker (con `mvn spring-boot:run`) y Docker sí expone ese puerto al host.

## Notas de implementación

- CORS está limitado a `http://localhost:5173` en `WebSocketConfig.java` — cámbialo por tu dominio real en producción.
- El username se guarda en `session.getAttributes()`, no en un `Map` aparte.
- Cada mensaje entrante genera un span (`ws.handle-message`) con tags según su `type`.
