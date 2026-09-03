package com.example.dispute.workflow.formalsinkarchitecturefixture;

import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@FormalSinkArchitectureFixture
class QualifiedCallAndMethodReferenceAssembly {

    @Bean
    Object qualifiedFormalActivity() {
        return FixtureFormalFactory.formalActivity();
    }

    @Bean
    Supplier<Object> formalActivityReference() {
        return FixtureFormalFactory::formalActivity;
    }
}
