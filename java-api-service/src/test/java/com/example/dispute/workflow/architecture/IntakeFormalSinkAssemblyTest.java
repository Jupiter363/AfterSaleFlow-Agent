package com.example.dispute.workflow.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(
        packages = "com.example.dispute",
        importOptions = ImportOption.DoNotIncludeTests.class)
class IntakeFormalSinkAssemblyTest {

    private static final String OWNED_PACKAGE_PREFIX = "com.example.dispute.";
    private static final String FIXTURE_PACKAGE =
            "com.example.dispute.workflow.formalsinkarchitecturefixture";
    private static final String TEMPORAL_WORKER = "io.temporal.worker.Worker";
    private static final String REGISTER_ACTIVITIES = "registerActivitiesImplementations";
    private static final String SPRING_COMPONENT = "org.springframework.stereotype.Component";
    private static final String SPRING_CONFIGURATION =
            "org.springframework.context.annotation.Configuration";
    private static final String SPRING_BEAN = "org.springframework.context.annotation.Bean";
    private static final String SPRING_BEAN_FACTORY =
            "org.springframework.beans.factory.BeanFactory";
    private static final String SERVICES_PATH = "META-INF/services/";

    private static final ServiceDescriptorCatalog PRODUCTION_SERVICE_DESCRIPTORS =
            scanProductionServiceDescriptors();

    private static final Set<String> FORMAL_ROOT_SIMPLE_NAMES =
            Set.of(
                    "IntakeFormalCommitPort",
                    "IntakeFormalBranchCommitPort",
                    "IntakeTurnFinalizationPort",
                    "IntakeGraphResultFinalizer",
                    "IntakeAgentRunDomainResultCommitter");

    private static final Set<String> DISCOVERY_ANNOTATIONS =
            Set.of(
                    SPRING_COMPONENT,
                    SPRING_CONFIGURATION,
                    "org.springframework.boot.autoconfigure.AutoConfiguration",
                    "org.springframework.boot.autoconfigure.SpringBootApplication",
                    "org.springframework.boot.context.properties.ConfigurationProperties",
                    "jakarta.annotation.ManagedBean",
                    "jakarta.inject.Named",
                    "jakarta.inject.Singleton",
                    "javax.annotation.ManagedBean",
                    "javax.inject.Named",
                    "javax.inject.Singleton");

    private static final Set<String> REFLECTIVE_CLASS_METHODS =
            Set.of(
                    "forName",
                    "getConstructor",
                    "getDeclaredConstructor",
                    "getField",
                    "getDeclaredField",
                    "getMethod",
                    "getDeclaredMethod",
                    "newInstance");

    private static final Set<String> SPRING_DYNAMIC_LOOKUP_METHODS =
            Set.of(
                    "getBean",
                    "getBeanNamesForType",
                    "getBeansOfType",
                    "getBeansWithAnnotation",
                    "findAnnotationOnBean");

    @ArchTest
    static final ArchRule ASSEMBLY_ROOTS_MUST_NOT_REACH_A_FORMAL_INTAKE_SINK =
            noFormalSinkAssemblyRule(PRODUCTION_SERVICE_DESCRIPTORS.ownedProviderNames());

    @ArchTest
    static void productionServiceDescriptorsMustResolveEveryOwnedProvider(JavaClasses classes) {
        ServiceProviderResolution resolution =
                resolveServiceProviders(PRODUCTION_SERVICE_DESCRIPTORS, classes);
        requireEveryOwnedProviderToResolve(resolution);
        assertThat(resolution.externalProviderRegistrations())
                .as("non-owned providers are explicitly recorded and excluded from owned roots")
                .allMatch(registration -> registration.contains(" -> "));
    }

    @Test
    void compiledFixturesProveBytecodeCoverageAndSafeComparisonAssembly() {
        JavaClasses fixtures =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.OnlyIncludeTests())
                        .importPackages(FIXTURE_PACKAGE);

        ServiceDescriptorCatalog injectedDescriptors =
                parseServiceDescriptors(
                        Map.of(
                                "com.vendor.Plugin",
                                """
                                # The contract and provider names intentionally reveal no formal role.
                                com.example.dispute.workflow.formalsinkarchitecturefixture.OpaqueProvider
                                com.example.dispute.workflow.formalsinkarchitecturefixture.SafeIntakeRoomActivitiesMetricsProvider
                                com.vendor.ExternalMetricsProvider
                                """));
        ServiceProviderResolution injectedResolution =
                resolveServiceProviders(injectedDescriptors, fixtures);
        assertThat(injectedResolution.missingOwnedProviderRegistrations()).isEmpty();
        assertThat(injectedResolution.externalProviderRegistrations())
                .contains("com.vendor.Plugin -> com.vendor.ExternalMetricsProvider");
        ArchRule fixtureRule =
                noFormalSinkAssemblyRule(injectedResolution.ownedProviderNames());

        String violations =
                String.join(
                        "\n",
                        fixtureRule
                                .evaluate(fixtures)
                                .getFailureReport()
                                .getDetails());

        assertThat(violations)
                .contains("StaticImportedFactoryBeanAssembly")
                .contains("StaticFieldAliasRegistrar")
                .contains("StaticWildcardNestedFactoryAssembly")
                .contains("QualifiedCallAndMethodReferenceAssembly")
                .contains("CrossFileWrapperAssembly")
                .contains("WorkerRegistrationMethodReferenceAssembly")
                .contains("MetaAnnotatedFormalAssembly")
                .contains("FixtureFormalFactory")
                .contains("CrossFileFormalDelegate")
                .contains("IntakeFormalCommitPort")
                .contains(
                        "formal Intake sink is reachable: "
                                + FIXTURE_PACKAGE
                                + ".OpaqueProvider -> "
                                + FIXTURE_PACKAGE
                                + ".CrossFileFormalWrapper -> "
                                + FIXTURE_PACKAGE
                                + ".CrossFileFormalDelegate -> "
                                + "com.example.dispute.workflow.application.intake.IntakeFormalCommitPort")
                .doesNotContain("SafeComparisonAssembly")
                .doesNotContain("LocalShadowingSafeRegistrar")
                .doesNotContain("SafeComparisonActivities")
                .doesNotContain("SafeIntakeRoomActivitiesMetricsProvider");

        assertThat(violations)
                .contains(
                        "ServiceLoaderHiddenProviderAssembly -> "
                                + FIXTURE_PACKAGE
                                + ".IntakeServiceProviderLoader -> java.util.ServiceLoader.load")
                .contains(
                        "ReflectiveFormalAdapterAssembly -> "
                                + FIXTURE_PACKAGE
                                + ".FormalAdapterClassResolver -> java.lang.Class.forName")
                .contains(
                        "SpringStringBeanLookupAssembly -> "
                                + FIXTURE_PACKAGE
                                + ".FormalBeanNameResolver -> "
                                + "org.springframework.context.ApplicationContext.getBean");

        assertShortestChain(
                fixtures,
                "StaticImportedFactoryBeanAssembly",
                "StaticImportedFactoryBeanAssembly",
                "FixtureFormalFactory",
                "IntakeFormalCommitPort");
        assertShortestChain(
                fixtures,
                "OpaqueProvider",
                "OpaqueProvider",
                "CrossFileFormalWrapper",
                "CrossFileFormalDelegate",
                "IntakeFormalCommitPort");
        assertShortestChain(
                fixtures,
                "StaticFieldAliasRegistrar",
                "StaticFieldAliasRegistrar",
                "FixtureFormalFactory",
                "IntakeFormalCommitPort");
        assertShortestChain(
                fixtures,
                "StaticWildcardNestedFactoryAssembly",
                "StaticWildcardNestedFactoryAssembly",
                "Nested",
                "IntakeFormalCommitPort");
        assertShortestChain(
                fixtures,
                "QualifiedCallAndMethodReferenceAssembly",
                "QualifiedCallAndMethodReferenceAssembly",
                "FixtureFormalFactory",
                "IntakeFormalCommitPort");
        assertShortestChain(
                fixtures,
                "CrossFileWrapperAssembly",
                "CrossFileWrapperAssembly",
                "CrossFileFormalWrapper",
                "CrossFileFormalDelegate",
                "IntakeFormalCommitPort");
        assertShortestChain(
                fixtures,
                "WorkerRegistrationMethodReferenceAssembly",
                "WorkerRegistrationMethodReferenceAssembly",
                "CrossFileFormalWrapper",
                "CrossFileFormalDelegate",
                "IntakeFormalCommitPort");
        assertShortestChain(
                fixtures,
                "MetaAnnotatedFormalAssembly",
                "MetaAnnotatedFormalAssembly",
                "CrossFileFormalWrapper",
                "CrossFileFormalDelegate",
                "IntakeFormalCommitPort");

        assertThat(
                        isAssemblyRoot(
                                fixtures.get(
                                        FIXTURE_PACKAGE
                                                + ".WorkerRegistrationMethodReferenceAssembly")))
                .isTrue();
        assertThat(
                        isAssemblyRoot(
                                fixtures.get(FIXTURE_PACKAGE + ".MetaAnnotatedFormalAssembly")))
                .isTrue();

        JavaClass neutralContract =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(
                                "com.example.dispute.workflow.temporal.room.intake")
                        .get("com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities");
        JavaClass comparisonAdapter = fixtures.get(FIXTURE_PACKAGE + ".SafeComparisonActivities");
        assertThat(isAssemblyRoot(neutralContract)).isFalse();
        assertThat(isAssemblyRoot(comparisonAdapter)).isFalse();
        assertThat(shortestFormalSinkChain(comparisonAdapter)).isEmpty();

        assertThatThrownBy(
                        () ->
                                parseServiceDescriptors(
                                        Map.of("com.vendor.Plugin", "not-a-java-binary-name!")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid provider binary name");

        ServiceDescriptorCatalog missingOwnedProvider =
                parseServiceDescriptors(
                        Map.of(
                                "com.vendor.Plugin",
                                FIXTURE_PACKAGE + ".MissingOwnedProvider"));
        ServiceProviderResolution missingResolution =
                resolveServiceProviders(missingOwnedProvider, fixtures);
        assertThatThrownBy(() -> requireEveryOwnedProviderToResolve(missingResolution))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(
                        "com.vendor.Plugin -> " + FIXTURE_PACKAGE + ".MissingOwnedProvider");
    }

    private static void assertShortestChain(
            JavaClasses classes, String rootSimpleName, String... expectedSimpleNames) {
        JavaClass root = classes.get(FIXTURE_PACKAGE + "." + rootSimpleName);
        List<String> actualSimpleNames =
                shortestFormalSinkChain(root).orElseThrow().stream()
                        .map(JavaClass::getSimpleName)
                        .toList();
        assertThat(actualSimpleNames).containsExactly(expectedSimpleNames);
    }

    private static ArchRule noFormalSinkAssemblyRule(Set<String> serviceProviderRoots) {
        return ArchRuleDefinition.classes()
                .that(
                        new DescribedPredicate<>("are discoverable assembly or Temporal activity registration roots") {
                            @Override
                            public boolean test(JavaClass javaClass) {
                                return isAssemblyRoot(javaClass, serviceProviderRoots);
                            }
                        })
                .should(
                        new ArchCondition<>(
                                "not reach a formal Intake sink or use dynamic assembly APIs") {
                            @Override
                            public void check(JavaClass root, ConditionEvents events) {
                                shortestFormalSinkChain(root)
                                        .ifPresent(
                                                chain ->
                                                        events.add(
                                                                SimpleConditionEvent.violated(
                                                                        root,
                                                                        "formal Intake sink is reachable: "
                                                                                + formatChain(chain))));
                                addDynamicAssemblyViolations(root, events);
                            }
                        })
                .because(
                        "Phase 4 permits only DISABLED or signed synthetic SHADOW assembly and "
                                + "forbids a discoverable formal Intake Finalizer sink");
    }

    private static boolean isAssemblyRoot(JavaClass javaClass) {
        return isAssemblyRoot(
                javaClass, PRODUCTION_SERVICE_DESCRIPTORS.ownedProviderNames());
    }

    private static boolean isAssemblyRoot(
            JavaClass javaClass, Set<String> serviceProviderRoots) {
        return hasDiscoverableClassAnnotation(javaClass)
                || declaresBeanMethod(javaClass)
                || isWorkerRegistrationRoot(javaClass)
                || serviceProviderRoots.contains(javaClass.getName());
    }

    private static boolean hasDiscoverableClassAnnotation(JavaClass javaClass) {
        ArrayDeque<JavaClass> pending = new ArrayDeque<>(directAnnotationTypes(javaClass));
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            JavaClass annotationType = pending.removeFirst();
            if (!visited.add(annotationType.getName())) {
                continue;
            }
            if (isDiscoveryAnnotation(annotationType.getName())) {
                return true;
            }
            pending.addAll(directAnnotationTypes(annotationType));
        }
        return false;
    }

    private static List<JavaClass> directAnnotationTypes(JavaClass javaClass) {
        return javaClass.getAnnotations().stream()
                .map(JavaAnnotation::getRawType)
                .sorted(Comparator.comparing(JavaClass::getName))
                .toList();
    }

    private static boolean isDiscoveryAnnotation(String annotationName) {
        return DISCOVERY_ANNOTATIONS.contains(annotationName)
                || annotationName.startsWith("jakarta.enterprise.context.")
                || annotationName.startsWith("javax.enterprise.context.");
    }

    private static boolean declaresBeanMethod(JavaClass javaClass) {
        return javaClass.getMethods().stream()
                .anyMatch(method -> method.isAnnotatedWith(SPRING_BEAN));
    }

    private static boolean isWorkerRegistrationRoot(JavaClass javaClass) {
        return javaClass.getMethodCallsFromSelf().stream()
                        .anyMatch(IntakeFormalSinkAssemblyTest::isTemporalActivityRegistration)
                || javaClass.getMethodReferencesFromSelf().stream()
                        .anyMatch(IntakeFormalSinkAssemblyTest::isTemporalActivityRegistration);
    }

    private static boolean isTemporalActivityRegistration(JavaCodeUnitAccess<?> access) {
        return access.getTargetOwner().getName().equals(TEMPORAL_WORKER)
                && access.getName().equals(REGISTER_ACTIVITIES);
    }

    private static Optional<List<JavaClass>> shortestFormalSinkChain(JavaClass root) {
        return reachableOwnedPaths(root).stream()
                .filter(path -> isFormalRoot(path.javaClass()))
                .map(PathNode::path)
                .findFirst();
    }

    private static List<PathNode> reachableOwnedPaths(JavaClass root) {
        ArrayDeque<PathNode> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        List<PathNode> reachable = new ArrayList<>();
        pending.add(new PathNode(root, List.of(root)));
        visited.add(root.getName());

        while (!pending.isEmpty()) {
            PathNode current = pending.removeFirst();
            reachable.add(current);

            directOwnedDependencies(current.javaClass()).stream()
                    .filter(dependency -> visited.add(dependency.getName()))
                    .forEach(
                            dependency -> {
                                List<JavaClass> path = new ArrayList<>(current.path());
                                path.add(dependency);
                                pending.addLast(new PathNode(dependency, List.copyOf(path)));
                            });
        }
        return reachable;
    }

    private static void addDynamicAssemblyViolations(
            JavaClass root, ConditionEvents events) {
        for (PathNode reachable : reachableOwnedPaths(root)) {
            for (JavaCodeUnitAccess<?> access :
                    forbiddenDynamicAssemblyAccesses(reachable.javaClass())) {
                events.add(
                        SimpleConditionEvent.violated(
                                root,
                                "dynamic assembly API is reachable: "
                                        + formatChain(reachable.path())
                                        + " -> "
                                        + access.getTargetOwner().getName()
                                        + "."
                                        + access.getName()));
            }
        }
    }

    private static List<JavaCodeUnitAccess<?>> forbiddenDynamicAssemblyAccesses(
            JavaClass javaClass) {
        List<JavaCodeUnitAccess<?>> accesses = new ArrayList<>();
        accesses.addAll(javaClass.getMethodCallsFromSelf());
        accesses.addAll(javaClass.getConstructorCallsFromSelf());
        accesses.addAll(javaClass.getMethodReferencesFromSelf());
        return accesses.stream()
                .filter(IntakeFormalSinkAssemblyTest::isForbiddenDynamicAssemblyAccess)
                .sorted(
                        Comparator.comparing(
                                        (JavaCodeUnitAccess<?> access) ->
                                                access.getTargetOwner().getName())
                                .thenComparing(JavaCodeUnitAccess::getName)
                                .thenComparingInt(JavaCodeUnitAccess::getLineNumber))
                .toList();
    }

    private static boolean isForbiddenDynamicAssemblyAccess(JavaCodeUnitAccess<?> access) {
        JavaClass owner = access.getTargetOwner();
        String ownerName = owner.getName();
        String methodName = access.getName();
        if (ownerName.equals("java.util.ServiceLoader")
                || ownerName.startsWith("java.util.ServiceLoader$")) {
            return true;
        }
        if (ownerName.equals("java.lang.Class")
                && REFLECTIVE_CLASS_METHODS.contains(methodName)) {
            return true;
        }
        if (owner.isAssignableTo("java.lang.ClassLoader")
                && (methodName.equals("loadClass") || methodName.equals("<init>"))) {
            return true;
        }
        if ((ownerName.equals("java.lang.reflect.Constructor")
                        && methodName.equals("newInstance"))
                || (ownerName.equals("java.lang.reflect.Method")
                        && methodName.equals("invoke"))
                || (ownerName.equals("java.lang.reflect.Field")
                        && Set.of("get", "set").contains(methodName))
                || (ownerName.equals("java.lang.reflect.Proxy")
                        && methodName.equals("newProxyInstance"))) {
            return true;
        }
        if (ownerName.startsWith("java.lang.invoke.MethodHandles")
                || (ownerName.equals("java.lang.invoke.MethodType")
                        && methodName.equals("fromMethodDescriptorString"))) {
            return true;
        }
        return SPRING_DYNAMIC_LOOKUP_METHODS.contains(methodName)
                && owner.isAssignableTo(SPRING_BEAN_FACTORY);
    }

    private static ServiceDescriptorCatalog scanProductionServiceDescriptors() {
        List<ServiceDescriptor> descriptors = new ArrayList<>();
        Arrays.stream(System.getProperty("java.class.path", "").split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .sorted(Comparator.comparing(Path::toString))
                .forEach(path -> scanClassPathEntry(path, descriptors));
        return new ServiceDescriptorCatalog(descriptors);
    }

    private static void scanClassPathEntry(
            Path classPathEntry, List<ServiceDescriptor> descriptors) {
        if (Files.isDirectory(classPathEntry)) {
            if (isMainClassesDirectory(classPathEntry)) {
                scanServiceDescriptorDirectory(classPathEntry, descriptors);
            }
            return;
        }
        if (Files.isRegularFile(classPathEntry)
                && classPathEntry.getFileName().toString().endsWith(".jar")) {
            scanServiceDescriptorJar(classPathEntry, descriptors);
        }
    }

    private static boolean isMainClassesDirectory(Path classPathEntry) {
        String normalized = classPathEntry.toString().replace('\\', '/');
        return normalized.endsWith("/target/classes");
    }

    private static void scanServiceDescriptorDirectory(
            Path classesDirectory, List<ServiceDescriptor> descriptors) {
        Path serviceRoot = classesDirectory.resolve("META-INF").resolve("services");
        if (!Files.isDirectory(serviceRoot)) {
            return;
        }
        try (var paths = Files.list(serviceRoot)) {
            for (Path descriptor :
                    paths.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(Path::toString))
                            .toList()) {
                descriptors.add(
                        parseServiceDescriptor(
                                descriptor.toString(),
                                descriptor.getFileName().toString(),
                                Files.readString(descriptor, StandardCharsets.UTF_8)));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "cannot scan production service descriptors under " + serviceRoot,
                    failure);
        }
    }

    private static void scanServiceDescriptorJar(
            Path jarPath, List<ServiceDescriptor> descriptors) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            List<JarEntry> serviceEntries =
                    jar.stream()
                            .filter(entry -> !entry.isDirectory())
                            .filter(entry -> entry.getName().startsWith(SERVICES_PATH))
                            .filter(
                                    entry ->
                                            !entry.getName()
                                                    .substring(SERVICES_PATH.length())
                                                    .contains("/"))
                            .sorted(Comparator.comparing(JarEntry::getName))
                            .toList();
            for (JarEntry entry : serviceEntries) {
                String contract = entry.getName().substring(SERVICES_PATH.length());
                String source = jarPath + "!/" + entry.getName();
                String content =
                        new String(
                                jar.getInputStream(entry).readAllBytes(),
                                StandardCharsets.UTF_8);
                descriptors.add(parseServiceDescriptor(source, contract, content));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "cannot scan production service descriptors in " + jarPath,
                    failure);
        }
    }

    private static ServiceDescriptorCatalog parseServiceDescriptors(
            Map<String, String> descriptors) {
        List<ServiceDescriptor> parsed = new ArrayList<>();
        descriptors.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        descriptor ->
                                parsed.add(
                                        parseServiceDescriptor(
                                                "injected:" + descriptor.getKey(),
                                                descriptor.getKey(),
                                                descriptor.getValue())));
        return new ServiceDescriptorCatalog(parsed);
    }

    private static ServiceDescriptor parseServiceDescriptor(
            String source, String contractName, String content) {
        requireJavaBinaryName(contractName, "service contract", source, 0);
        Set<String> providers = new TreeSet<>();
        String[] lines = content.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String provider = lines[index].split("#", 2)[0].trim();
            if (provider.isEmpty()) {
                continue;
            }
            requireJavaBinaryName(provider, "provider", source, index + 1);
            providers.add(provider);
        }
        return new ServiceDescriptor(source, contractName, providers);
    }

    private static void requireJavaBinaryName(
            String candidate, String kind, String source, int lineNumber) {
        if (!SourceVersion.isName(candidate, SourceVersion.RELEASE_21)) {
            String location = lineNumber > 0 ? source + ":" + lineNumber : source;
            throw new IllegalArgumentException(
                    "invalid " + kind + " binary name '" + candidate + "' in " + location);
        }
    }

    private static ServiceProviderResolution resolveServiceProviders(
            ServiceDescriptorCatalog catalog, JavaClasses importedClasses) {
        Set<String> ownedProviders = new TreeSet<>();
        Set<String> missingOwnedProviders = new TreeSet<>();
        Set<String> externalProviders = new TreeSet<>();
        for (ServiceDescriptor descriptor : catalog.descriptors()) {
            for (String provider : descriptor.providerNames()) {
                String registration = descriptor.contractName() + " -> " + provider;
                if (!provider.startsWith(OWNED_PACKAGE_PREFIX)) {
                    externalProviders.add(registration);
                } else if (importedClasses.contain(provider)) {
                    ownedProviders.add(provider);
                } else {
                    missingOwnedProviders.add(registration);
                }
            }
        }
        return new ServiceProviderResolution(
                Set.copyOf(ownedProviders),
                List.copyOf(missingOwnedProviders),
                List.copyOf(externalProviders));
    }

    private static void requireEveryOwnedProviderToResolve(
            ServiceProviderResolution resolution) {
        assertThat(resolution.missingOwnedProviderRegistrations())
                .as("owned META-INF/services providers must resolve to imported production classes")
                .isEmpty();
    }

    private static List<JavaClass> directOwnedDependencies(JavaClass javaClass) {
        return javaClass.getDirectDependenciesFromSelf().stream()
                .map(Dependency::getTargetClass)
                .filter(target -> target.getName().startsWith(OWNED_PACKAGE_PREFIX))
                .collect(
                        Collectors.toMap(
                                JavaClass::getName,
                                target -> target,
                                (first, ignored) -> first))
                .values()
                .stream()
                .sorted(Comparator.comparing(JavaClass::getName))
                .toList();
    }

    private static boolean isFormalRoot(JavaClass javaClass) {
        return FORMAL_ROOT_SIMPLE_NAMES.contains(javaClass.getSimpleName())
                || javaClass.getSimpleName().startsWith("JdbcIntakeFormal");
    }

    private static String formatChain(List<JavaClass> chain) {
        return chain.stream().map(JavaClass::getName).collect(Collectors.joining(" -> "));
    }

    private record PathNode(JavaClass javaClass, List<JavaClass> path) {}

    private record ServiceDescriptor(
            String source, String contractName, Set<String> providerNames) {

        private ServiceDescriptor {
            providerNames = Set.copyOf(providerNames);
        }
    }

    private record ServiceDescriptorCatalog(List<ServiceDescriptor> descriptors) {

        private ServiceDescriptorCatalog {
            descriptors = List.copyOf(descriptors);
        }

        private Set<String> ownedProviderNames() {
            return descriptors.stream()
                    .flatMap(descriptor -> descriptor.providerNames().stream())
                    .filter(provider -> provider.startsWith(OWNED_PACKAGE_PREFIX))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    private record ServiceProviderResolution(
            Set<String> ownedProviderNames,
            List<String> missingOwnedProviderRegistrations,
            List<String> externalProviderRegistrations) {}
}
