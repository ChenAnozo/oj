package com.yupi.yuojbackendquestionservice.config;

import com.yupi.yuojbackendquestionservice.websocket.JudgeWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import javax.annotation.Resource;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private JudgeWebSocketHandler judgeWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(judgeWebSocketHandler, "/ws/judge")
                .setAllowedOrigins("*");
    }
}
