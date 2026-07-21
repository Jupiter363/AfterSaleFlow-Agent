package com.example.dispute.workflow.formalsinkarchitecturefixture;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

@Configuration
class SpringStringBeanLookupAssembly {

    private final FormalBeanNameResolver resolver;

    SpringStringBeanLookupAssembly(FormalBeanNameResolver resolver) {
        this.resolver = resolver;
    }

    Object hiddenFormalBean() {
        return resolver.resolve();
    }
}

final class FormalBeanNameResolver {

    private final ApplicationContext context;

    FormalBeanNameResolver(ApplicationContext context) {
        this.context = context;
    }

    Object resolve() {
        return context.getBean("formalIntakeFinalizer");
    }
}
