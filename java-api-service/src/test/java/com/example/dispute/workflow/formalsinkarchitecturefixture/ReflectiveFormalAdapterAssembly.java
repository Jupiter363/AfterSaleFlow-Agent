package com.example.dispute.workflow.formalsinkarchitecturefixture;

import jakarta.inject.Named;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

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

@Configuration
class MixedSafeAndRuntimeReflectiveAssembly {

    Object resolve(String runtimeFormalClassName) throws ClassNotFoundException {
        Class.forName("java.util.ArrayList");
        return Class.forName(runtimeFormalClassName);
    }
}

@Configuration
class SameLineAmbiguousDynamicAssembly {

    Object resolve(String runtimeFormalClassName) throws ClassNotFoundException {
        return List.of(Class.forName("java.util.ArrayList"), Class.forName(runtimeFormalClassName));
    }
}

@Configuration
class MixedSafeAndRuntimeConstructorAssembly {

    Object resolve(Constructor<?> runtimeConstructor) throws ReflectiveOperationException {
        Class.forName("java.util.ArrayList");
        return runtimeConstructor.newInstance();
    }
}

@Configuration
class MixedSafeAndRuntimeMethodAssembly {

    Object resolve(Method runtimeMethod, Object receiver) throws ReflectiveOperationException {
        Class.forName("java.util.ArrayList");
        return runtimeMethod.invoke(receiver);
    }
}

@Configuration
class MixedSafeAndRuntimeFieldAssembly {

    Object resolve(Field runtimeField, Object receiver) throws ReflectiveOperationException {
        Class.forName("java.util.ArrayList");
        return runtimeField.get(receiver);
    }
}

@Configuration
class UnresolvedConfigurationDynamicAssembly {

    Object resolve(String runtimeFormalClassName) throws ClassNotFoundException {
        return Class.forName(runtimeFormalClassName);
    }
}

@Component
class UnresolvedComponentDynamicAssembly {

    Object resolve(String runtimeFormalClassName) throws ClassNotFoundException {
        return Class.forName(runtimeFormalClassName);
    }
}

@Named
class UnresolvedNamedDynamicAssembly {

    Object resolve(String runtimeFormalClassName) throws ClassNotFoundException {
        return Class.forName(runtimeFormalClassName);
    }
}
