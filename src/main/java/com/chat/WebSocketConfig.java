package com.chat;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket   // Activa el soporte WebSocket en Spring
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatHandler chatHandler;

    public WebSocketConfig(ChatHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
            .addHandler(chatHandler, "/chat")  // → ws://localhost:8080/chat
            .setAllowedOrigins("http://localhost:5173");
            // En producción: reemplaza con tu dominio real
            // .setAllowedOrigins("https://tuapp.com")
    }
}
