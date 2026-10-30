package com.yupi.yuojbackendquestionservice.websocket;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.yupi.yuojbackendmodel.entity.QuestionSubmit;
import com.yupi.yuojbackendmodel.enums.QuestionSubmitStatusEnum;
import com.yupi.yuojbackendquestionservice.service.QuestionSubmitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class JudgeWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private JudgeWebSocketServer judgeWebSocketServer;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long questionSubmitId = getQuestionSubmitId(session);
        if (questionSubmitId == null) {
            log.warn("WebSocket connection missing questionSubmitId parameter");
            try { session.close(CloseStatus.BAD_DATA); } catch (Exception ignored) {}
            return;
        }
        judgeWebSocketServer.register(questionSubmitId, session);
        sendCurrentStatus(session, questionSubmitId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 客户端不主动发消息
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long questionSubmitId = getQuestionSubmitId(session);
        if (questionSubmitId != null) {
            judgeWebSocketServer.unregister(questionSubmitId, session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket transport error: sessionId={}", session.getId(), exception);
        Long questionSubmitId = getQuestionSubmitId(session);
        if (questionSubmitId != null) {
            judgeWebSocketServer.unregister(questionSubmitId, session);
        }
    }

    private void sendCurrentStatus(WebSocketSession session, Long questionSubmitId) {
        try {
            QuestionSubmit submit = questionSubmitService.getById(questionSubmitId);
            if (submit != null) {
                Integer status = submit.getStatus();
                Map<String, Object> msg = new HashMap<>();
                msg.put("questionSubmitId", questionSubmitId);
                msg.put("status", status);

                if (QuestionSubmitStatusEnum.SUCCEED.getValue().equals(status)
                    || QuestionSubmitStatusEnum.FAILED.getValue().equals(status)
                    || QuestionSubmitStatusEnum.SYSTEM_ERROR.getValue().equals(status)) {
                    msg.put("type", "result");
                    if (submit.getJudgeInfo() != null) {
                        msg.put("judgeInfo", JSONUtil.parseObj(submit.getJudgeInfo()));
                    }
                } else {
                    msg.put("type", "status");
                    msg.put("statusText", QuestionSubmitStatusEnum.getEnumByValue(status).getText());
                }

                session.sendMessage(new TextMessage(JSONUtil.toJsonStr(msg)));
            }
        } catch (Exception e) {
            log.error("Failed to send current status for questionSubmitId={}", questionSubmitId, e);
        }
    }

    private Long getQuestionSubmitId(WebSocketSession session) {
        String query = session.getUri().getQuery();
        if (StrUtil.isBlank(query)) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "questionSubmitId".equals(kv[0])) {
                return Long.parseLong(kv[1]);
            }
        }
        return null;
    }
}
