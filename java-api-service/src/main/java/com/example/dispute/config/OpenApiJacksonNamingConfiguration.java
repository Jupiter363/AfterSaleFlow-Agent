package com.example.dispute.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Makes generated OpenAPI schemas use the same property names as runtime JSON. */
@Configuration(proxyBeanMethods = false)
public class OpenApiJacksonNamingConfiguration {

    /**
     * Springdoc's ModelConverterRegistrar replaces the default ModelResolver with a converter bean
     * of the same class, so schema discovery inherits the application's configured ObjectMapper.
     */
    @Bean
    ModelResolver applicationObjectMapperModelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper);
    }
}
