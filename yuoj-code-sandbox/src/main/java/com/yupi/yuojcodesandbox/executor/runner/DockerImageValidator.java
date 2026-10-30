package com.yupi.yuojcodesandbox.executor.runner;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.LinkedHashSet;

/** Validate configured runtime images once, before the service accepts requests. */
@Component
public class DockerImageValidator implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(DockerImageValidator.class);
    private final DockerClient client;
    private final String javaImage;
    private final String cppImage;

    public DockerImageValidator(DockerClient client,
            @Value("${codesandbox.java-image:openjdk:8-alpine}") String javaImage,
            @Value("${codesandbox.cpp-image:gcc:latest}") String cppImage) {
        this.client = client;
        this.javaImage = javaImage;
        this.cppImage = cppImage;
    }

    @Override
    public void afterPropertiesSet() {
        for (String image : new LinkedHashSet<>(Arrays.asList(javaImage, cppImage))) {
            try {
                client.inspectImageCmd(image).exec();
            } catch (NotFoundException e) {
                throw new IllegalStateException("Docker runtime image missing at startup: " + image, e);
            }
            log.info("Docker runtime image verified at startup: {}", image);
        }
    }
}
