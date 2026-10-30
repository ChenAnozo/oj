package com.yupi.yuojbackendquestionservice.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Value("${oj.judge.timeout-minutes:5}")
    private long timeoutMinutes;

    @Bean
    public Queue codeDelayQueue() {
        return QueueBuilder.durable("code_delay_queue")
                .ttl((int) (timeoutMinutes * 60 * 1000))
                .deadLetterExchange("code_dlx_exchange")
                .deadLetterRoutingKey("code_timeout")
                .build();
    }
}
