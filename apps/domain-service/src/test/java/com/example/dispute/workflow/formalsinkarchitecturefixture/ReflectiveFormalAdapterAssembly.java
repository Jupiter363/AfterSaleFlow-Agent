package com.example.dispute.workflow.formalsinkarchitecturefixture;

import com.example.dispute.workflow.application.intake.IntakeFormalCommitPort;
import jakarta.inject.Named;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration
@FormalSinkArchitectureFixture
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
@FormalSinkArchitectureFixture
class NeutralReflectiveHelperAssembly {

    Object instantiate() throws ReflectiveOperationException {
        Class<?> helper = Class.forName(
                "com.example.dispute.workflow.formalsinkarchitecturefixture.NeutralReflectiveHelper");
        return helper.getDeclaredConstructor().newInstance();
    }
}

final class NeutralReflectiveHelper {

    Object instantiate() throws ReflectiveOperationException {
        Class<?> finalizer = Class.forName(
                "com.example.dispute.workflow.formalsinkarchitecturefixture.HiddenFinalizerAdapter");
        return finalizer.getDeclaredConstructor().newInstance();
    }
}

final class HiddenFinalizerAdapter {

    static final IntakeFormalCommitPort FORMAL_COMMIT_PORT = null;

    HiddenFinalizerAdapter() {}
}

@Configuration
@FormalSinkArchitectureFixture
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
@FormalSinkArchitectureFixture
class MixedSafeAndRuntimeReflectiveAssembly {

    Object resolve(String runtimeFormalClassName) throws ClassNotFoundException {
        Class.forName("java.util.ArrayList");
        return Class.forName(runtimeFormalClassName);
    }
}

@Configuration
@FormalSinkArchitectureFixture
class SameLineAmbiguousDynamicAssembly {

    Object resolve(String runtimeFormalClassName) throws ClassNotFoundException {
        return List.of(Class.forName("java.util.ArrayList"), Class.forName(runtimeFormalClassName));
    }
}

@Configuration
@FormalSinkArchitectureFixture
class MixedSafeAndRuntimeConstructorAssembly {

    Object resolve(Constructor<?> runtimeConstructor) throws ReflectiveOperationException {
        Class.forName("java.util.ArrayList");
        return runtimeConstructor.newInstance();
    }
}

@Configuration
@FormalSinkArchitectureFixture
class MixedSafeAndRuntimeMethodAssembly {

    Object resolve(Method runtimeMethod, Object receiver) throws ReflectiveOperationException {
        Class.forName("java.util.ArrayList");
        return runtimeMethod.invoke(receiver);
    }
}

@Configuration
@FormalSinkArchitectureFixture
class MixedSafeAndRuntimeFieldAssembly {

    Object resolve(Field runtimeField, Object receiver) throws ReflectiveOperationException {
        Class.forName("java.util.ArrayList");
        return runtimeField.get(receiver);
    }
}

@Configuration
@FormalSinkArchitectureFixture
class UnresolvedConfigurationDynamicAssembly {

    Object resolve(String runtimeFormalClassName) throws ClassNotFoundException {
        return Class.forName(runtimeFormalClassName);
    }
}

@Component
@FormalSinkArchitectureFixture
class UnresolvedComponentDynamicAssembly {

    Object resolve(String runtimeFormalClassName) throws ClassNotFoundException {
        return Class.forName(runtimeFormalClassName);
    }
}

@Named
@FormalSinkArchitectureFixture
class UnresolvedNamedDynamicAssembly {

    Object resolve(String runtimeFormalClassName) throws ClassNotFoundException {
        return Class.forName(runtimeFormalClassName);
    }
}
