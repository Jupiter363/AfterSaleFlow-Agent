package com.example.dispute.workflow.formalsinkarchitecturefixture;

import static com.example.dispute.workflow.formalsinkarchitecturefixture.FixtureFormalFactory.Nested.*;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@FormalSinkArchitectureFixture
class StaticWildcardNestedFactoryAssembly {

    @Bean
    Object nestedFormalActivityBean() {
        return nestedFormalActivity();
    }
}
