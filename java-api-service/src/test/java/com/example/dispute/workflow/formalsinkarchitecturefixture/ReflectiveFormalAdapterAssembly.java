package com.example.dispute.workflow.formalsinkarchitecturefixture;

import org.springframework.context.annotation.Configuration;

@Configuration
class ReflectiveFormalAdapterAssembly {

    private final FormalAdapterClassResolver resolver;

    ReflectiveFormalAdapterAssembly(FormalAdapterClassResolver resolver) {
        this.resolver = resolver;
    }

    Object hiddenFormalAdapter() throws ReflectiveOperationException {
        return resolver.instantiate();
    }
}

final class FormalAdapterClassResolver {

    Object instantiate() throws ReflectiveOperationException {
        Class<?> type = Class.forName(
                "com.example.dispute.workflow.activity.intake.IntakeRoomActivitiesAdapter");
        return type.getDeclaredConstructor().newInstance();
    }
}

@Configuration
class SafeReflectiveAssembly {

    Object safeUtility() throws ReflectiveOperationException {
        return new SafeUtilityClassResolver().instantiate();
    }
}

final class SafeUtilityClassResolver {

    Object instantiate() throws ReflectiveOperationException {
        Class<?> type = Class.forName("java.util.ArrayList");
        return type.getDeclaredConstructor().newInstance();
    }
}
