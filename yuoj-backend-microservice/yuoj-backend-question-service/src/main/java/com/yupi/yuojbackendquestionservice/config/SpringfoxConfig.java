package com.yupi.yuojbackendquestionservice.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import springfox.documentation.spring.web.plugins.WebMvcRequestHandlerProvider;

import java.lang.reflect.Field;
import java.util.List;

@Configuration
public class SpringfoxConfig {

    @Bean
    public static BeanPostProcessor springfoxHandlerProviderBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof WebMvcRequestHandlerProvider) {
                    customize((WebMvcRequestHandlerProvider) bean);
                }
                return bean;
            }

            private void customize(WebMvcRequestHandlerProvider bean) {
                try {
                    Field field = WebMvcRequestHandlerProvider.class.getDeclaredField("handlerMappings");
                    field.setAccessible(true);
                    List<RequestMappingInfoHandlerMapping> mappings =
                            (List<RequestMappingInfoHandlerMapping>) field.get(bean);
                    if (mappings != null) {
                        mappings.removeIf(m -> m.getPatternParser() != null);
                    }
                } catch (Exception ignored) {
                }
            }
        };
    }
}
