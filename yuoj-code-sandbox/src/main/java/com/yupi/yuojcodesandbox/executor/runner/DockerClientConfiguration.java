package com.yupi.yuojcodesandbox.executor.runner;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerClientConfiguration {
    @Bean(destroyMethod = "close")
    public DockerClient dockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        ApacheDockerHttpClient transport = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
        return DockerClientImpl.getInstance(config, transport);
    }
}
