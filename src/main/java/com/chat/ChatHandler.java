package com.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * CopyOnWriteArraySet es thread-safe: Spring puede llamar a estos
 * métodos desde distintos hilos simultáneamente.
 *
 * El username de cada sesión se guarda en session.getAttributes(),
 * así no necesitamos un Map aparte para asociar sesión → usuario.
 */
@Component
public class ChatHandler extends TextWebSocketHandler {

    private static final String USERNAME_ATTR = "username";

    // Conjunto de todas las sesiones WebSocket activas
    private final Set<WebSocketSession> sessions =
            new CopyOnWriteArraySet<>();

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Se llama cuando un cliente se CONECTA (aún sin username) ──
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        System.out.println("🟢 Conexión abierta: " + session.getId());
    }

    // ── Se llama cuando llega un MENSAJE de texto ──────
    @Override
    protected void handleTextMessage(WebSocketSession session,
                                      TextMessage message) throws Exception {
        try {
            ChatMessage incoming = mapper.readValue(
                    message.getPayload(), ChatMessage.class);

            switch (incoming.getType() == null ? "message" : incoming.getType()) {

                case "join" -> handleJoin(session, incoming);
                case "typing" -> handleTyping(session, incoming);
                default -> handleChatMessage(incoming);
            }

        } catch (Exception e) {
            System.err.println("❌ Mensaje inválido: " + e.getMessage());
        }
    }

    // ── Un usuario elige nombre y entra al chat ────────
    private void handleJoin(WebSocketSession session, ChatMessage incoming) throws Exception {
        session.getAttributes().put(USERNAME_ATTR, incoming.getUsername());
        System.out.println("👋 " + incoming.getUsername() + " se unió | Total: " + sessions.size());

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
    private void handleChatMessage(ChatMessage incoming) throws Exception {
        System.out.println("💬 [" + incoming.getUsername() + "] " + incoming.getText());

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
        System.out.println("🔴 Desconectado: " + session.getId() + " | Total: " + sessions.size());

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
            }
        }
    }
}
