package com.chat;

import java.util.List;

// Representa la estructura de cada mensaje JSON
// que viaja entre cliente y servidor
public class ChatMessage {

    private String type;        // "join" | "message" | "system" | "typing"
    private String username;
    private String text;
    private String timestamp;
    private int    count;       // Nº de usuarios conectados
    private List<String> users; // Lista de usuarios conectados (para "system")
    private boolean typing;     // true = empezó a escribir, false = dejó de escribir

    // Constructor vacío obligatorio para Jackson
    public ChatMessage() {}

    public ChatMessage(String type, String text, int count) {
        this.type  = type;
        this.text  = text;
        this.count = count;
    }

    // ── Getters y Setters ──────────────────────────────
    public String getType()                    { return type; }
    public void   setType(String type)           { this.type = type; }

    public String getUsername()                 { return username; }
    public void   setUsername(String username)   { this.username = username; }

    public String getText()                     { return text; }
    public void   setText(String text)           { this.text = text; }

    public String getTimestamp()               { return timestamp; }
    public void   setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public int    getCount()                    { return count; }
    public void   setCount(int count)           { this.count = count; }

    public List<String> getUsers()               { return users; }
    public void   setUsers(List<String> users)    { this.users = users; }

    public boolean isTyping()                    { return typing; }
    public void    setTyping(boolean typing)      { this.typing = typing; }
}
