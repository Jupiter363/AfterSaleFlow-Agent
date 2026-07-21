package com.example.dispute.workflow.formalsinkarchitecturefixture;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CrossFileWrapperAssembly {

    @Bean
    CrossFileFormalWrapper formalWrapper(CrossFileFormalDelegate delegate) {
        return new CrossFileFormalWrapper(delegate);
    }
}
