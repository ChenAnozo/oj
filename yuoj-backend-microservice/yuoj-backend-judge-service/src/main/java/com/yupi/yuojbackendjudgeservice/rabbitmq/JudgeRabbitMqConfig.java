package com.yupi.yuojbackendjudgeservice.rabbitmq;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JudgeRabbitMqConfig {
    @Bean
    public Declarables judgeQueueTopology() {
        DirectExchange exchange = new DirectExchange("code_exchange", true, false);
        Queue queue = new Queue("code_queue", true);
        return new Declarables(exchange, queue,
                BindingBuilder.bind(queue).to(exchange).with("my_routingKey"));
    }
}
