package com.yupi.yuojbackendquestionservice.websocket;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class JudgeWebSocketServer {

    private final ConcurrentHashMap<Long, List<WebSocketSession>> sessionMap = new ConcurrentHashMap<>();

    public void register(Long questionSubmitId, WebSocketSession session) {
        sessionMap.computeIfAbsent(questionSubmitId, k -> new CopyOnWriteArrayList<>()).add(session);
        log.info("JudgeWebSocket connected: questionSubmitId={}, sessionId={}", questionSubmitId, session.getId());
    }

    public void unregister(Long questionSubmitId, WebSocketSession session) {
        List<WebSocketSession> sessions = sessionMap.get(questionSubmitId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionMap.remove(questionSubmitId);
            }
        }
        log.info("JudgeWebSocket disconnected: questionSubmitId={}, sessionId={}", questionSubmitId, session.getId());
    }

    public void sendMessage(Long questionSubmitId, Object message) {
        List<WebSocketSession> sessions = sessionMap.getOrDefault(questionSubmitId, Collections.emptyList());
        String json = JSONUtil.toJsonStr(message);
        TextMessage textMessage = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    synchronized (session) {
                        session.sendMessage(textMessage);
                    }
                } catch (IOException e) {
                    log.error("WebSocket send failed: questionSubmitId={}, sessionId={}", questionSubmitId, session.getId(), e);
                }
            }
        }
    }
}
