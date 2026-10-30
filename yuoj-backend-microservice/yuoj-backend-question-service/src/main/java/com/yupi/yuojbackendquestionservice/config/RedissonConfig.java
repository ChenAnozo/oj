package com.yupi.yuojbackendquestionservice.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host}")
    private String host;

    @Value("${spring.redis.port}")
    private int port;

    @Value("${spring.redis.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.setCodec(new JsonJacksonCodec());
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(2);
        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }
        return Redisson.create(config);
    }
}
