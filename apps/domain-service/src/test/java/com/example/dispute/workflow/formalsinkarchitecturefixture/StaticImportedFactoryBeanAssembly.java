package com.example.dispute.workflow.formalsinkarchitecturefixture;

import static com.example.dispute.workflow.formalsinkarchitecturefixture.FixtureFormalFactory.formalActivity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@FormalSinkArchitectureFixture
class StaticImportedFactoryBeanAssembly {

    @Bean
    Object formalActivityBean() {
        return formalActivity();
    }
}
