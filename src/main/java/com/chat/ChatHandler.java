package com.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

/**
 * ChatHandler — gestiona el ciclo de vida de cada sesión WebSocket.
 *
 * Instrumentado con:
 *  - Métricas (Micrometer → Prometheus → Grafana)
 *  - Trazas   (Micrometer Tracing/Brave → Zipkin)
 *  - Logs estructurados (Logback JSON → Logstash → Elasticsearch → Kibana)
 *
 * El username de cada sesión se guarda en session.getAttributes(),
 * así no necesitamos un Map aparte para asociar sesión → usuario.
 */
@Component
public class ChatHandler extends TextWebSocketHandler {

    private static final String USERNAME_ATTR = "username";
    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);

    // Conjunto de todas las sesiones WebSocket activas
    private final Set<WebSocketSession> sessions =
            new CopyOnWriteArraySet<>();

    private final ObjectMapper mapper = new ObjectMapper();
    private final Tracer tracer;

    // ── Métricas ─────────────────────────────────────────
    private final Counter messagesReceived;
    private final Counter messagesBroadcast;

    public ChatHandler(MeterRegistry registry, Tracer tracer) {
        this.tracer = tracer;

        // Counter: acumula el total de mensajes recibidos (solo sube)
        this.messagesReceived = Counter.builder("ws.messages.received")
                .description("Total de mensajes recibidos del cliente")
                .register(registry);

        // Counter: mensajes enviados en broadcast (puede ser N por mensaje)
        this.messagesBroadcast = Counter.builder("ws.messages.broadcast")
                .description("Total de mensajes enviados a clientes")
                .register(registry);

        // Gauge: valor actual de sesiones abiertas (sube y baja)
        Gauge.builder("ws.sessions.active", sessions, Set::size)
                .description("Sesiones WebSocket activas en este momento")
                .register(registry);
    }

    // ── Se llama cuando un cliente se CONECTA (aún sin username) ──
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        // El Gauge de ws.sessions.active se actualiza automáticamente
        log.info("WS_CONNECTED",
                StructuredArguments.kv("session_id", session.getId()),
                StructuredArguments.kv("active_sessions", sessions.size())
        );
    }

    // ── Se llama cuando llega un MENSAJE de texto ──────
    @Override
    protected void handleTextMessage(WebSocketSession session,
                                      TextMessage message) throws Exception {
        messagesReceived.increment(); // ← +1 por cada mensaje entrante

        String type = "message";
        try {
            // Necesitamos el type antes de abrir el span, para taguearlo
            ChatMessage peek = mapper.readValue(message.getPayload(), ChatMessage.class);
            type = peek.getType() == null ? "message" : peek.getType();

            // Span de esta operación — visible en Zipkin con sus tags
            Span span = tracer.nextSpan()
                    .name("ws.handle-message")
                    .tag("session.id", session.getId())
                    .tag("message.type", type)
                    .start();

            try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
                switch (type) {
                    case "join" -> handleJoin(session, peek, span);
                    case "typing" -> handleTyping(session, peek);
                    default -> handleChatMessage(peek, span);
                }
            } catch (Exception e) {
                span.error(e); // ← marca el span como FAILED en Zipkin
                throw e;
            } finally {
                span.end(); // ← SIEMPRE cerrar el span, incluso si hay error
            }

        } catch (Exception e) {
            System.err.println("❌ Mensaje inválido: " + e.getMessage());
        }
    }

    // ── Un usuario elige nombre y entra al chat ────────
    private void handleJoin(WebSocketSession session, ChatMessage incoming, Span span) throws Exception {
        session.getAttributes().put(USERNAME_ATTR, incoming.getUsername());

        span.tag("chat.username", incoming.getUsername());

        log.info("WS_JOINED",
                StructuredArguments.kv("username", incoming.getUsername()),
                StructuredArguments.kv("active_sessions", sessions.size())
        );

        ChatMessage joinMsg = new ChatMessage("system",
                incoming.getUsername() + " se unió al chat", sessions.size());
        joinMsg.setUsers(connectedUsernames());
        broadcast(joinMsg);
    }

    // ── Alguien empezó o dejó de escribir ──────────────
    private void handleTyping(WebSocketSession session, ChatMessage incoming) throws Exception {
        ChatMessage typingMsg = new ChatMessage();
        typingMsg.setType("typing");
        typingMsg.setUsername(incoming.getUsername());
        typingMsg.setTyping(incoming.isTyping());
        broadcastToOthers(session, typingMsg);
    }

    // ── Mensaje normal de chat ──────────────────────────
    private void handleChatMessage(ChatMessage incoming, Span span) throws Exception {
        // Agregar contexto al span — se verá en la UI de Zipkin
        span.tag("chat.username", incoming.getUsername());
        span.tag("chat.text_length", String.valueOf(incoming.getText().length()));
        span.tag("chat.active_users", String.valueOf(sessions.size()));

        log.info("WS_MESSAGE",
                StructuredArguments.kv("username", incoming.getUsername()),
                StructuredArguments.kv("message_length", incoming.getText().length()),
                StructuredArguments.kv("active_sessions", sessions.size())
        );

        ChatMessage outgoing = new ChatMessage();
        outgoing.setType("message");
        outgoing.setUsername(incoming.getUsername());
        outgoing.setText(incoming.getText());
        outgoing.setTimestamp(Instant.now().toString());

        broadcast(outgoing);
    }

    // ── Se llama cuando un cliente se DESCONECTA ───────
    @Override
    public void afterConnectionClosed(WebSocketSession session,
                                       CloseStatus status) throws Exception {
        String username = (String) session.getAttributes().get(USERNAME_ATTR);
        sessions.remove(session);

        log.info("WS_DISCONNECTED",
                StructuredArguments.kv("session_id", session.getId()),
                StructuredArguments.kv("close_status", status.getCode()),
                StructuredArguments.kv("active_sessions", sessions.size())
        );

        // Solo avisamos al resto si el usuario alcanzó a ponerse un nombre
        if (username != null) {
            ChatMessage leaveMsg = new ChatMessage("system",
                    username + " se desconectó", sessions.size());
            leaveMsg.setUsers(connectedUsernames());
            broadcast(leaveMsg);
        }
    }

    // ── Usernames de todas las sesiones que ya se unieron ──
    private List<String> connectedUsernames() {
        return sessions.stream()
                .map(s -> (String) s.getAttributes().get(USERNAME_ATTR))
                .filter(u -> u != null)
                .collect(Collectors.toList());
    }

    // ── Broadcast: envía a TODAS las sesiones abiertas ─
    private void broadcast(ChatMessage msg) throws Exception {
        send(msg, sessions);
    }

    // ── Broadcast a todos MENOS al que envió (para "typing") ─
    private void broadcastToOthers(WebSocketSession sender, ChatMessage msg) throws Exception {
        Set<WebSocketSession> others = sessions.stream()
                .filter(s -> !s.getId().equals(sender.getId()))
                .collect(Collectors.toSet());
        send(msg, others);
    }

    private void send(ChatMessage msg, Set<WebSocketSession> targets) throws Exception {
        String json = mapper.writeValueAsString(msg);
        TextMessage frame = new TextMessage(json);

        for (WebSocketSession s : targets) {
            if (s.isOpen()) {
                synchronized (s) {  // WebSocketSession NO es thread-safe al escribir
                    s.sendMessage(frame);
                }
                messagesBroadcast.increment(); // ← +1 por cada cliente que recibe
            }
        }
    }
}
