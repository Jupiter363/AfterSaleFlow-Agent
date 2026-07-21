package com.example.dispute.workflow.formalsinkarchitecturefixture;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
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

@Configuration
class SafeSpringBeanLookupAssembly {

    private final SafeBeanNameResolver resolver;

    SafeSpringBeanLookupAssembly(SafeBeanNameResolver resolver) {
        this.resolver = resolver;
    }

    Object safeBean() {
        return resolver.resolve();
    }
}

final class SafeBeanNameResolver {

    private final ApplicationContext context;

    SafeBeanNameResolver(ApplicationContext context) {
        this.context = context;
    }

    Object resolve() {
        Object named = context.getBean("safeComparisonActivities");
        Object typed = context.getBean(SafeComparisonActivities.class);
        return named != null ? named : typed;
    }
}

class UnresolvedBeanLookupAssembly {

    @Bean
    Object runtimeBean(ApplicationContext context, String runtimeBeanName) {
        return context.getBean(runtimeBeanName);
    }
}
