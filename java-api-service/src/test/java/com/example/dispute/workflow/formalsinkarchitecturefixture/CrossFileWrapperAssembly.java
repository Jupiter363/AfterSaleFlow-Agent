package com.example.dispute.workflow.formalsinkarchitecturefixture;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CrossFileWrapperAssembly {

    private final CrossFileFormalWrapper wrapper;

    CrossFileWrapperAssembly(CrossFileFormalWrapper wrapper) {
        this.wrapper = wrapper;
    }

    @Bean
    Object formalWrapperBean() {
        return wrapper;
    }
}
