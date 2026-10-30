package com.yupi.yuojbackendquestionservice.rabbitmq;

import cn.hutool.json.JSONUtil;
import com.rabbitmq.client.Channel;
import com.yupi.yuojbackendmodel.codesandbox.JudgeInfo;
import com.yupi.yuojbackendmodel.entity.QuestionSubmit;
import com.yupi.yuojbackendmodel.enums.QuestionSubmitStatusEnum;
import com.yupi.yuojbackendquestionservice.service.QuestionSubmitService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class TimeoutMessageConsumer {

    @Resource
    private QuestionSubmitService questionSubmitService;

    @SneakyThrows
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(name = "code_timeout_queue", durable = "true"),
                    exchange = @Exchange(name = "code_dlx_exchange", type = ExchangeTypes.DIRECT, durable = "true"),
                    key = "code_timeout"
            ),
            ackMode = "MANUAL"
    )
    public void handleTimeout(String message, Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        long questionSubmitId = Long.parseLong(message);
        log.warn("收到超时检查消息，questionSubmitId={}", questionSubmitId);

        QuestionSubmit submit = questionSubmitService.getById(questionSubmitId);
        if (submit == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        boolean updated = questionSubmitService.updateStatusCasNoNotify(
                questionSubmitId,
                QuestionSubmitStatusEnum.WAITING.getValue(),
                QuestionSubmitStatusEnum.SYSTEM_ERROR.getValue());
        if (!updated) {
            updated = questionSubmitService.updateStatusCasNoNotify(
                    questionSubmitId,
                    QuestionSubmitStatusEnum.RUNNING.getValue(),
                    QuestionSubmitStatusEnum.SYSTEM_ERROR.getValue());
        }

        if (updated) {
            QuestionSubmit update = new QuestionSubmit();
            update.setId(questionSubmitId);
            update.setStatus(QuestionSubmitStatusEnum.SYSTEM_ERROR.getValue());
            JudgeInfo judgeInfo = new JudgeInfo();
            judgeInfo.setTime(0L);
            judgeInfo.setMemory(0L);
            update.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
            questionSubmitService.updateSubmitAndNotify(update);
            log.info("判题超时已标记，questionSubmitId={}", questionSubmitId);
        } else {
            log.info("超时检查时状态已终态，跳过，questionSubmitId={}", questionSubmitId);
        }
        channel.basicAck(deliveryTag, false);
    }
}
