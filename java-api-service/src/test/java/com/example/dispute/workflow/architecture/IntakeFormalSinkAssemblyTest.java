package com.example.dispute.workflow.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;
import javax.lang.model.SourceVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;

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
    private static final String SPRING_TEST_COMPONENT =
            "org.springframework.boot.test.context.TestComponent";
    private static final String SPRING_CONFIGURATION =
            "org.springframework.context.annotation.Configuration";
    private static final String SPRING_BEAN = "org.springframework.context.annotation.Bean";
    private static final String SPRING_BEAN_FACTORY =
            "org.springframework.beans.factory.BeanFactory";
    private static final String SERVICES_PATH = "META-INF/services/";
    private static final String BOOT_CLASSES_SERVICES_PATH =
            "BOOT-INF/classes/META-INF/services/";
    private static final String BOOT_LIB_PATH = "BOOT-INF/lib/";
    private static final String MANIFEST_PATH = JarFile.MANIFEST_NAME;
    private static final int MAX_NESTED_ARCHIVE_DEPTH = 8;
    private static final int MAX_DYNAMIC_CLASS_BYTES = 4_194_304;
    private static final int MAX_EOCD_BYTES = 65_557;
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final long ZIP64_EOCD_LOCATOR_SIGNATURE = 0x07064b50L;
    private static final long CENTRAL_FILE_HEADER_SIGNATURE = 0x02014b50L;
    private static final long LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L;

    private static final ScanLimits PRODUCTION_SCAN_LIMITS =
            new ScanLimits(
                    1_048_576,
                    1_048_576,
                    67_108_864,
                    1_073_741_824,
                    8_192,
                    4_096,
                    32,
                    67_108_864,
                    5_000_000,
                    2_048,
                    2_000_000,
                    100_000,
                    268_435_456);

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

    private static final Map<DynamicCodeUnitKey, DynamicMethodEvidence>
            DYNAMIC_TARGET_EVIDENCE = new ConcurrentHashMap<>();

    @ArchTest
    static void assemblyRootsMustNotReachAFormalIntakeSink(JavaClasses classes) {
        noFormalSinkAssemblyRule(
                        PRODUCTION_SERVICE_DESCRIPTORS.ownedProviderNames(), classes)
                .check(classes);
    }

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
    void compiledFixturesProveBytecodeCoverageAndSafeComparisonAssembly(
            @TempDir Path tempDirectory) throws IOException {
        JavaClasses fixtures =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.OnlyIncludeTests())
                        .importPackages(FIXTURE_PACKAGE);
        assertThat(
                        fixtures.stream()
                                .filter(javaClass -> !javaClass.isAnnotation())
                                .filter(IntakeFormalSinkAssemblyTest::hasDiscoverableClassAnnotation)
                                .filter(
                                        javaClass ->
                                                !hasTransitiveAnnotation(
                                                        javaClass, SPRING_TEST_COMPONENT))
                                .map(JavaClass::getName)
                                .sorted()
                                .toList())
                .as("component-like architecture fixtures must stay out of real Spring contexts")
                .isEmpty();

        String combinedDescriptor =
                """
                \ufeff# A single leading BOM, comments, blanks, duplicates, and nested names are legal.

                com.example.dispute.workflow.formalsinkarchitecturefixture.OpaqueProvider
                com.example.dispute.workflow.formalsinkarchitecturefixture.OpaqueProvider$NestedOpaqueProvider
                com.example.dispute.workflow.formalsinkarchitecturefixture.OpaqueProvider$NestedOpaqueProvider
                com.example.dispute.workflow.formalsinkarchitecturefixture.SafeIntakeRoomActivitiesMetricsProvider
                com.vendor.ExternalMetricsProvider
                """;
        ServiceDescriptorCatalog injectedDescriptors =
                parseServiceDescriptors(
                        Map.of(
                                "com.vendor.Plugin",
                                combinedDescriptor));
        ServiceDescriptorCatalog scannedDescriptors =
                createAndScanServiceDescriptorFixtures(tempDirectory, combinedDescriptor);
        assertThat(scannedDescriptors.ownedProviderNames())
                .contains(
                        FIXTURE_PACKAGE + ".ManifestOnlyFormalProvider",
                        FIXTURE_PACKAGE + ".HardlinkManifestFormalProvider");
        ServiceDescriptorCatalog allFixtureDescriptors =
                ServiceDescriptorCatalog.merge(injectedDescriptors, scannedDescriptors);
        ServiceProviderResolution injectedResolution =
                resolveServiceProviders(allFixtureDescriptors, fixtures);
        assertThat(injectedResolution.missingOwnedProviderRegistrations()).isEmpty();
        assertThat(injectedResolution.externalProviderRegistrations())
                .contains("com.vendor.Plugin -> com.vendor.ExternalMetricsProvider");
        ArchRule fixtureRule =
                noFormalSinkAssemblyRule(
                        injectedResolution.ownedProviderNames(), fixtures);

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
                .contains("ManifestOnlyFormalProvider")
                .contains("HardlinkManifestFormalProvider")
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
                .contains(
                        "formal Intake sink is reachable: "
                                + FIXTURE_PACKAGE
                                + ".OpaqueProvider$NestedOpaqueProvider -> "
                                + FIXTURE_PACKAGE
                                + ".CrossFileFormalWrapper -> "
                                + FIXTURE_PACKAGE
                                + ".CrossFileFormalDelegate -> "
                                + "com.example.dispute.workflow.application.intake.IntakeFormalCommitPort")
                .contains(
                        "formal Intake sink is reachable: "
                                + FIXTURE_PACKAGE
                                + ".ManifestOnlyFormalProvider -> "
                                + FIXTURE_PACKAGE
                                + ".CrossFileFormalWrapper -> "
                                + FIXTURE_PACKAGE
                                + ".CrossFileFormalDelegate -> "
                                + "com.example.dispute.workflow.application.intake.IntakeFormalCommitPort")
                .contains(
                        "formal Intake sink is reachable: "
                                + FIXTURE_PACKAGE
                                + ".HardlinkManifestFormalProvider -> "
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
                        "formal Intake sink is reachable: "
                                + FIXTURE_PACKAGE
                                + ".NeutralReflectiveHelperAssembly -> "
                                + FIXTURE_PACKAGE
                                + ".NeutralReflectiveHelper -> "
                                + FIXTURE_PACKAGE
                                + ".HiddenFinalizerAdapter -> "
                                + "com.example.dispute.workflow.application.intake.IntakeFormalCommitPort")
                .contains(
                        "SpringStringBeanLookupAssembly -> "
                                + FIXTURE_PACKAGE
                                + ".FormalBeanNameResolver -> "
                                + "org.springframework.context.ApplicationContext.getBean");
        assertThat(violations)
                .contains(
                        "UnresolvedWorkerDynamicAssembly -> java.lang.Class.forName")
                .contains(
                        "UnresolvedBeanLookupAssembly -> "
                                + "org.springframework.context.ApplicationContext.getBean")
                .contains(
                        "MixedSafeAndRuntimeReflectiveAssembly -> java.lang.Class.forName")
                .contains(
                        "SameLineAmbiguousDynamicAssembly -> java.lang.Class.forName")
                .contains(
                        "MixedSafeAndRuntimeConstructorAssembly -> "
                                + "java.lang.reflect.Constructor.newInstance")
                .contains(
                        "MixedSafeAndRuntimeMethodAssembly -> "
                                + "java.lang.reflect.Method.invoke")
                .contains(
                        "MixedSafeAndRuntimeFieldAssembly -> java.lang.reflect.Field.get")
                .contains(
                        "UnresolvedConfigurationDynamicAssembly -> java.lang.Class.forName")
                .contains(
                        "UnresolvedComponentDynamicAssembly -> java.lang.Class.forName")
                .contains(
                        "UnresolvedNamedDynamicAssembly -> java.lang.Class.forName")
                .doesNotContain("SafeServiceLoaderAssembly")
                .doesNotContain("SafeServiceProviderLoader")
                .doesNotContain("SafeReflectiveAssembly")
                .doesNotContain("SafeUtilityClassResolver")
                .doesNotContain("SafeSpringBeanLookupAssembly")
                .doesNotContain("SafeBeanNameResolver");

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
        assertThat(shortestFormalSinkChain(comparisonAdapter, ownedClassIndex(fixtures))).isEmpty();

        assertThatThrownBy(
                        () ->
                                parseServiceDescriptors(
                                        Map.of("com.vendor.Plugin", "not-a-java-binary-name!")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid provider binary name");
        assertThatThrownBy(
                        () ->
                                parseServiceDescriptors(
                                        Map.of(
                                                "com.vendor.Plugin",
                                                "\ufeff" + FIXTURE_PACKAGE + ".OpaqueProvider\n\ufeff")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedded UTF-8 BOM");

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
                shortestFormalSinkChain(root, ownedClassIndex(classes)).orElseThrow().stream()
                        .map(JavaClass::getSimpleName)
                        .toList();
        assertThat(actualSimpleNames).containsExactly(expectedSimpleNames);
    }

    private static ArchRule noFormalSinkAssemblyRule(
            Set<String> serviceProviderRoots, JavaClasses importedClasses) {
        Map<String, JavaClass> ownedClasses = ownedClassIndex(importedClasses);
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
                                shortestFormalSinkChain(root, ownedClasses)
                                        .ifPresent(
                                                chain ->
                                                        events.add(
                                                                SimpleConditionEvent.violated(
                                                                        root,
                                                                        "formal Intake sink is reachable: "
                                                                                + formatChain(chain))));
                                addDynamicAssemblyViolations(
                                        root, serviceProviderRoots, ownedClasses, events);
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

    private static boolean hasTransitiveAnnotation(JavaClass javaClass, String annotationName) {
        ArrayDeque<JavaClass> pending = new ArrayDeque<>(directAnnotationTypes(javaClass));
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            JavaClass annotationType = pending.removeFirst();
            if (!visited.add(annotationType.getName())) {
                continue;
            }
            if (annotationName.equals(annotationType.getName())) {
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

    private static Optional<List<JavaClass>> shortestFormalSinkChain(
            JavaClass root, Map<String, JavaClass> ownedClasses) {
        return reachableOwnedPaths(root, ownedClasses).stream()
                .filter(path -> isFormalRoot(path.javaClass()))
                .map(PathNode::path)
                .findFirst();
    }

    private static List<PathNode> reachableOwnedPaths(
            JavaClass root, Map<String, JavaClass> ownedClasses) {
        ArrayDeque<PathNode> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        List<PathNode> reachable = new ArrayList<>();
        pending.add(new PathNode(root, List.of(root)));
        visited.add(root.getName());

        while (!pending.isEmpty()) {
            PathNode current = pending.removeFirst();
            reachable.add(current);

            List<JavaClass> directTargets =
                    new ArrayList<>(directOwnedDependencies(current.javaClass()));
            directTargets.addAll(
                    directOwnedDynamicTargets(current.javaClass(), ownedClasses));
            directTargets.stream()
                    .distinct()
                    .sorted(Comparator.comparing(JavaClass::getName))
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

    private static List<JavaClass> directOwnedDynamicTargets(
            JavaClass javaClass, Map<String, JavaClass> ownedClasses) {
        return dynamicAssemblyAccesses(javaClass).stream()
                .map(IntakeFormalSinkAssemblyTest::dynamicTargetEvidence)
                .filter(evidence -> evidence.inspected() && !evidence.ambiguous())
                .flatMap(
                        evidence ->
                                dynamicClassTargetNames(evidence).stream())
                .map(ownedClasses::get)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(JavaClass::getName))
                .toList();
    }

    private static void addDynamicAssemblyViolations(
            JavaClass root,
            Set<String> serviceProviderRoots,
            Map<String, JavaClass> ownedClasses,
            ConditionEvents events) {
        for (PathNode reachable : reachableOwnedPaths(root, ownedClasses)) {
            for (JavaCodeUnitAccess<?> access :
                    forbiddenDynamicAssemblyAccesses(
                            reachable.javaClass(), root, serviceProviderRoots, ownedClasses)) {
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
            JavaClass javaClass,
            JavaClass root,
            Set<String> serviceProviderRoots,
            Map<String, JavaClass> ownedClasses) {
        return dynamicAssemblyAccesses(javaClass).stream()
                .filter(
                        access ->
                                isForbiddenDynamicAssemblyAccess(
                                        access,
                                        root,
                                        serviceProviderRoots,
                                        ownedClasses))
                .toList();
    }

    private static List<JavaCodeUnitAccess<?>> dynamicAssemblyAccesses(
            JavaClass javaClass) {
        List<JavaCodeUnitAccess<?>> accesses = new ArrayList<>();
        accesses.addAll(javaClass.getMethodCallsFromSelf());
        accesses.addAll(javaClass.getConstructorCallsFromSelf());
        accesses.addAll(javaClass.getMethodReferencesFromSelf());
        return accesses.stream()
                .filter(IntakeFormalSinkAssemblyTest::isDynamicAssemblyApi)
                .sorted(
                        Comparator.comparing(
                                        (JavaCodeUnitAccess<?> access) ->
                                                access.getTargetOwner().getName())
                                .thenComparing(JavaCodeUnitAccess::getName)
                                .thenComparingInt(JavaCodeUnitAccess::getLineNumber))
                .toList();
    }

    private static boolean isForbiddenDynamicAssemblyAccess(
            JavaCodeUnitAccess<?> access,
            JavaClass root,
            Set<String> serviceProviderRoots,
            Map<String, JavaClass> ownedClasses) {
        DynamicTargetEvidence evidence = dynamicTargetEvidence(access);
        if (!evidence.inspected() || evidence.ambiguous()) {
            return true;
        }
        if (hasUnresolvedOwnedDynamicTarget(evidence, ownedClasses)) {
            return true;
        }
        if (hasFormalDynamicTarget(evidence)
                || shortestFormalSinkChain(access.getOriginOwner(), ownedClasses)
                        .isPresent()) {
            return true;
        }
        if (hasResolvedDynamicTarget(evidence)) {
            return false;
        }
        return isAssemblyRoot(root, serviceProviderRoots);
    }

    private static Map<String, JavaClass> ownedClassIndex(JavaClasses importedClasses) {
        return importedClasses.stream()
                .filter(javaClass -> javaClass.getName().startsWith(OWNED_PACKAGE_PREFIX))
                .collect(
                        Collectors.toUnmodifiableMap(
                                JavaClass::getName,
                                javaClass -> javaClass));
    }

    private static Set<String> dynamicClassTargetNames(
            DynamicTargetEvidence evidence) {
        return java.util.stream.Stream.concat(
                        evidence.stringConstants().stream(),
                        evidence.typeConstants().stream())
                .map(IntakeFormalSinkAssemblyTest::normalizeDynamicClassName)
                .filter(name -> name.startsWith(OWNED_PACKAGE_PREFIX))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeDynamicClassName(String candidate) {
        String normalized = candidate;
        while (normalized.endsWith("[]")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        if (normalized.startsWith("[L") && normalized.endsWith(";")) {
            normalized = normalized.substring(2, normalized.length() - 1);
        }
        return normalized.replace('/', '.');
    }

    private static boolean hasUnresolvedOwnedDynamicTarget(
            DynamicTargetEvidence evidence, Map<String, JavaClass> ownedClasses) {
        return dynamicClassTargetNames(evidence).stream()
                .anyMatch(name -> !ownedClasses.containsKey(name));
    }

    private static boolean isDynamicAssemblyApi(JavaCodeUnitAccess<?> access) {
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

    private static boolean hasFormalDynamicTarget(DynamicTargetEvidence evidence) {
        return evidence.stringConstants().stream()
                        .anyMatch(IntakeFormalSinkAssemblyTest::isFormalDynamicTargetName)
                || evidence.typeConstants().stream()
                        .anyMatch(IntakeFormalSinkAssemblyTest::isFormalDynamicTargetName);
    }

    private static boolean hasResolvedDynamicTarget(DynamicTargetEvidence evidence) {
        return !evidence.typeConstants().isEmpty()
                || evidence.stringConstants().stream()
                        .anyMatch(
                                value ->
                                        !value.isBlank()
                                                && value.chars()
                                                        .noneMatch(Character::isWhitespace));
    }

    private static boolean isFormalDynamicTargetName(String candidate) {
        String normalized = candidate.replace('$', '.');
        String simpleName =
                normalized.substring(normalized.lastIndexOf('.') + 1);
        String lowerCase = normalized.toLowerCase(Locale.ROOT);
        return FORMAL_ROOT_SIMPLE_NAMES.contains(simpleName)
                || simpleName.startsWith("JdbcIntakeFormal")
                || normalized.equals(
                        "com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities")
                || normalized.endsWith(".IntakeRoomActivitiesAdapter")
                || (lowerCase.contains("intake") && lowerCase.contains("formal"));
    }

    private static DynamicTargetEvidence dynamicTargetEvidence(
            JavaCodeUnitAccess<?> access) {
        JavaCodeUnit origin = access.getOrigin();
        DynamicCodeUnitKey key =
                new DynamicCodeUnitKey(
                        origin.getOwner().getName(),
                        origin.getName(),
                        origin.getDescriptor());
        DynamicMethodEvidence methodEvidence =
                DYNAMIC_TARGET_EVIDENCE.computeIfAbsent(
                        key, ignored -> readDynamicMethodEvidence(origin));
        if (!methodEvidence.inspected()) {
            return DynamicTargetEvidence.uninspected();
        }
        String targetDescriptor =
                access.getTarget().resolveMember().stream()
                        .filter(JavaCodeUnit.class::isInstance)
                        .map(JavaCodeUnit.class::cast)
                        .map(JavaCodeUnit::getDescriptor)
                        .findFirst()
                        .orElse(null);
        List<DynamicInvocationEvidence> signatureMatches =
                methodEvidence.invocations().stream()
                        .filter(
                                invocation ->
                                        invocation.matchesSignature(
                                                access.getTargetOwner().getName(),
                                                access.getName(),
                                                targetDescriptor))
                        .toList();
        List<DynamicInvocationEvidence> lineMatches =
                signatureMatches.stream()
                        .filter(invocation -> invocation.lineNumber() == access.getLineNumber())
                        .toList();
        List<DynamicInvocationEvidence> candidates =
                lineMatches.isEmpty() ? signatureMatches : lineMatches;
        if (candidates.size() != 1) {
            return candidates.isEmpty()
                    ? DynamicTargetEvidence.uninspected()
                    : DynamicTargetEvidence.ambiguousTarget();
        }
        return candidates.getFirst().targetEvidence();
    }

    private static DynamicMethodEvidence readDynamicMethodEvidence(JavaCodeUnit origin) {
        String resourceName =
                origin.getOwner().getName().replace('.', '/') + ".class";
        ClassLoader classLoader = IntakeFormalSinkAssemblyTest.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(resourceName)) {
            if (input == null) {
                return DynamicMethodEvidence.uninspected();
            }
            byte[] classBytes = input.readNBytes(MAX_DYNAMIC_CLASS_BYTES + 1);
            if (classBytes.length > MAX_DYNAMIC_CLASS_BYTES) {
                return DynamicMethodEvidence.uninspected();
            }
            List<DynamicInvocationEvidence> invocations = new ArrayList<>();
            boolean[] matchedOrigin = {false};
            ClassReader reader = new ClassReader(classBytes);
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(
                                int access,
                                String name,
                                String descriptor,
                                String signature,
                                String[] exceptions) {
                            if (!name.equals(origin.getName())
                                    || !descriptor.equals(origin.getDescriptor())) {
                                return null;
                            }
                            matchedOrigin[0] = true;
                            return new DynamicEvidenceMethodVisitor(
                                    access, descriptor, invocations);
                        }
                    },
                    ClassReader.SKIP_FRAMES);
            return new DynamicMethodEvidence(matchedOrigin[0], invocations);
        } catch (IOException | RuntimeException failure) {
            return DynamicMethodEvidence.uninspected();
        }
    }

    private static boolean isDynamicTargetInvocation(String owner, String methodName) {
        if (owner.equals("java/util/ServiceLoader") && methodName.equals("load")) {
            return true;
        }
        if (owner.equals("java/lang/Class") && methodName.equals("forName")) {
            return true;
        }
        if (owner.endsWith("ClassLoader") && methodName.equals("loadClass")) {
            return true;
        }
        if ((owner.startsWith("org/springframework/beans/factory/")
                        || owner.equals("org/springframework/context/ApplicationContext"))
                && SPRING_DYNAMIC_LOOKUP_METHODS.contains(methodName)) {
            return true;
        }
        return owner.startsWith("java/lang/invoke/MethodHandles")
                || (owner.equals("java/lang/invoke/MethodType")
                        && methodName.equals("fromMethodDescriptorString"))
                || (owner.equals("java/lang/reflect/Proxy")
                        && methodName.equals("newProxyInstance"));
    }

    private static boolean isDynamicBytecodeInvocation(String owner, String methodName) {
        if (isDynamicTargetInvocation(owner, methodName)
                || owner.startsWith("java/util/ServiceLoader")) {
            return true;
        }
        if (owner.equals("java/lang/Class")
                && REFLECTIVE_CLASS_METHODS.contains(methodName)) {
            return true;
        }
        if (owner.endsWith("ClassLoader")
                && (methodName.equals("loadClass") || methodName.equals("<init>"))) {
            return true;
        }
        return (owner.equals("java/lang/reflect/Constructor")
                        && methodName.equals("newInstance"))
                || (owner.equals("java/lang/reflect/Method")
                        && methodName.equals("invoke"))
                || (owner.equals("java/lang/reflect/Field")
                        && Set.of("get", "set").contains(methodName));
    }

    private static final class DynamicEvidenceMethodVisitor extends MethodVisitor {

        private final List<DynamicInvocationEvidence> invocations;
        private final List<DynamicStackValue> operandStack = new ArrayList<>();
        private final Map<Integer, DynamicStackValue> locals = new HashMap<>();
        private final Set<Integer> resolvedDeferredInvocations = new HashSet<>();
        private boolean ambiguousControlFlow;
        private int currentLine = -1;
        private int invocationOrdinal;

        private DynamicEvidenceMethodVisitor(
                int methodAccess,
                String methodDescriptor,
                List<DynamicInvocationEvidence> invocations) {
            super(Opcodes.ASM9);
            this.invocations = invocations;
            int localIndex = 0;
            if ((methodAccess & Opcodes.ACC_STATIC) == 0) {
                locals.put(localIndex++, DynamicStackValue.unresolved(1));
            }
            for (Type argumentType : Type.getArgumentTypes(methodDescriptor)) {
                locals.put(
                        localIndex,
                        DynamicStackValue.unresolved(argumentType.getSize()));
                localIndex += argumentType.getSize();
            }
        }

        @Override
        public void visitLineNumber(int line, org.springframework.asm.Label start) {
            currentLine = line;
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof String stringValue) {
                push(DynamicStackValue.stringConstant(stringValue));
            } else if (value instanceof Type typeValue
                    && (typeValue.getSort() == Type.OBJECT
                            || typeValue.getSort() == Type.ARRAY)) {
                push(DynamicStackValue.typeConstant(typeValue.getClassName()));
            } else {
                push(
                        DynamicStackValue.unresolved(
                                value instanceof Long || value instanceof Double ? 2 : 1));
            }
        }

        @Override
        public void visitVarInsn(int opcode, int variable) {
            switch (opcode) {
                case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD ->
                        push(locals.getOrDefault(variable, DynamicStackValue.unresolved(1)));
                case Opcodes.LLOAD, Opcodes.DLOAD ->
                        push(locals.getOrDefault(variable, DynamicStackValue.unresolved(2)));
                case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE ->
                        locals.put(variable, pop(1));
                case Opcodes.LSTORE, Opcodes.DSTORE -> locals.put(variable, pop(2));
                case Opcodes.RET -> invalidateProvenance();
                default -> invalidateProvenance();
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            switch (opcode) {
                case Opcodes.BIPUSH, Opcodes.SIPUSH ->
                        push(DynamicStackValue.unresolved(1));
                case Opcodes.NEWARRAY -> {
                    pop(1);
                    push(DynamicStackValue.unresolved(1));
                }
                default -> invalidateProvenance();
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            switch (opcode) {
                case Opcodes.NEW -> push(DynamicStackValue.unresolved(1));
                case Opcodes.ANEWARRAY -> {
                    pop(1);
                    push(DynamicStackValue.unresolved(1));
                }
                case Opcodes.CHECKCAST -> push(pop(1));
                case Opcodes.INSTANCEOF -> {
                    pop(1);
                    push(DynamicStackValue.unresolved(1));
                }
                default -> invalidateProvenance();
            }
        }

        @Override
        public void visitFieldInsn(
                int opcode, String owner, String name, String descriptor) {
            Type fieldType = Type.getType(descriptor);
            switch (opcode) {
                case Opcodes.GETSTATIC ->
                        push(DynamicStackValue.unresolved(fieldType.getSize()));
                case Opcodes.PUTSTATIC -> pop(fieldType.getSize());
                case Opcodes.GETFIELD -> {
                    pop(1);
                    push(DynamicStackValue.unresolved(fieldType.getSize()));
                }
                case Opcodes.PUTFIELD -> {
                    pop(fieldType.getSize());
                    pop(1);
                }
                default -> invalidateProvenance();
            }
        }

        @Override
        public void visitMethodInsn(
                int opcode,
                String owner,
                String name,
                String methodDescriptor,
                boolean isInterface) {
            Type[] argumentTypes = Type.getArgumentTypes(methodDescriptor);
            DynamicStackValue[] arguments = new DynamicStackValue[argumentTypes.length];
            for (int index = argumentTypes.length - 1; index >= 0; index--) {
                arguments[index] = pop(argumentTypes[index].getSize());
            }
            DynamicStackValue receiver =
                    opcode == Opcodes.INVOKESTATIC ? null : pop(1);
            boolean dynamicInvocation = isDynamicBytecodeInvocation(owner, name);
            if (!dynamicInvocation) {
                resolveDeferredInvocations(owner, name, receiver, arguments);
            }
            DynamicTargetEvidence targetEvidence =
                    dynamicInvocation
                            ? targetEvidence(
                                    owner,
                                    name,
                                    receiver,
                                    argumentTypes,
                                    arguments)
                            : DynamicTargetEvidence.unresolved();
            invocations.add(
                    new DynamicInvocationEvidence(
                            owner.replace('/', '.'),
                            name,
                            methodDescriptor,
                            currentLine,
                            invocationOrdinal++,
                            targetEvidence));

            Type returnType = Type.getReturnType(methodDescriptor);
            if (returnType.getSort() != Type.VOID) {
                DynamicTargetEvidence returnEvidence =
                        dynamicInvocation
                                ? targetEvidence
                                : carrierReturnEvidence(owner, name, receiver);
                push(
                        new DynamicStackValue(
                                returnType.getSize(), returnEvidence, Set.of()));
            }
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name,
                String descriptor,
                org.springframework.asm.Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
            Type[] argumentTypes = Type.getArgumentTypes(descriptor);
            for (int index = argumentTypes.length - 1; index >= 0; index--) {
                pop(argumentTypes[index].getSize());
            }
            Set<Integer> deferredInvocations = new HashSet<>();
            for (Object argument : bootstrapMethodArguments) {
                if (!(argument instanceof org.springframework.asm.Handle handle)) {
                    continue;
                }
                int invocationIndex = invocations.size();
                boolean dynamicHandle =
                        isDynamicBytecodeInvocation(
                                handle.getOwner(), handle.getName());
                invocations.add(
                        new DynamicInvocationEvidence(
                                handle.getOwner().replace('/', '.'),
                                handle.getName(),
                                handle.getDesc(),
                                currentLine,
                                invocationOrdinal++,
                                dynamicHandle
                                        ? DynamicTargetEvidence.ambiguousTarget()
                                        : DynamicTargetEvidence.unresolved()));
                if (dynamicHandle) {
                    deferredInvocations.add(invocationIndex);
                }
            }
            Type returnType = Type.getReturnType(descriptor);
            if (returnType.getSort() != Type.VOID) {
                push(
                        new DynamicStackValue(
                                returnType.getSize(),
                                DynamicTargetEvidence.unresolved(),
                                deferredInvocations));
            }
        }

        @Override
        public void visitJumpInsn(int opcode, org.springframework.asm.Label label) {
            invalidateProvenance();
        }

        @Override
        public void visitTableSwitchInsn(
                int min,
                int max,
                org.springframework.asm.Label defaultLabel,
                org.springframework.asm.Label... labels) {
            invalidateProvenance();
        }

        @Override
        public void visitLookupSwitchInsn(
                org.springframework.asm.Label defaultLabel,
                int[] keys,
                org.springframework.asm.Label[] labels) {
            invalidateProvenance();
        }

        @Override
        public void visitTryCatchBlock(
                org.springframework.asm.Label start,
                org.springframework.asm.Label end,
                org.springframework.asm.Label handler,
                String type) {
            invalidateProvenance();
        }

        @Override
        public void visitIincInsn(int variable, int increment) {
            locals.put(variable, DynamicStackValue.unresolved(1));
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            for (int index = 0; index < dimensions; index++) {
                pop(1);
            }
            push(DynamicStackValue.unresolved(1));
        }

        @Override
        public void visitInsn(int opcode) {
            switch (opcode) {
                case Opcodes.NOP -> {
                    // No stack effect.
                }
                case Opcodes.ACONST_NULL,
                                Opcodes.ICONST_M1,
                                Opcodes.ICONST_0,
                                Opcodes.ICONST_1,
                                Opcodes.ICONST_2,
                                Opcodes.ICONST_3,
                                Opcodes.ICONST_4,
                                Opcodes.ICONST_5,
                                Opcodes.FCONST_0,
                                Opcodes.FCONST_1,
                                Opcodes.FCONST_2 -> push(DynamicStackValue.unresolved(1));
                case Opcodes.LCONST_0,
                                Opcodes.LCONST_1,
                                Opcodes.DCONST_0,
                                Opcodes.DCONST_1 -> push(DynamicStackValue.unresolved(2));
                case Opcodes.IALOAD,
                                Opcodes.FALOAD,
                                Opcodes.AALOAD,
                                Opcodes.BALOAD,
                                Opcodes.CALOAD,
                                Opcodes.SALOAD -> arrayLoad(1);
                case Opcodes.LALOAD, Opcodes.DALOAD -> arrayLoad(2);
                case Opcodes.IASTORE,
                                Opcodes.FASTORE,
                                Opcodes.AASTORE,
                                Opcodes.BASTORE,
                                Opcodes.CASTORE,
                                Opcodes.SASTORE -> arrayStore(1);
                case Opcodes.LASTORE, Opcodes.DASTORE -> arrayStore(2);
                case Opcodes.POP -> pop(1);
                case Opcodes.POP2 -> popTwoSlots();
                case Opcodes.DUP -> duplicateTop();
                case Opcodes.DUP_X1 -> duplicateTopUnderOne();
                case Opcodes.DUP2 -> duplicateTwoSlots();
                case Opcodes.SWAP -> swapTop();
                case Opcodes.IADD,
                                Opcodes.ISUB,
                                Opcodes.IMUL,
                                Opcodes.IDIV,
                                Opcodes.IREM,
                                Opcodes.IAND,
                                Opcodes.IOR,
                                Opcodes.IXOR,
                                Opcodes.ISHL,
                                Opcodes.ISHR,
                                Opcodes.IUSHR,
                                Opcodes.FADD,
                                Opcodes.FSUB,
                                Opcodes.FMUL,
                                Opcodes.FDIV,
                                Opcodes.FREM -> binaryOperation(1);
                case Opcodes.LADD,
                                Opcodes.LSUB,
                                Opcodes.LMUL,
                                Opcodes.LDIV,
                                Opcodes.LREM,
                                Opcodes.LAND,
                                Opcodes.LOR,
                                Opcodes.LXOR,
                                Opcodes.LSHL,
                                Opcodes.LSHR,
                                Opcodes.LUSHR,
                                Opcodes.DADD,
                                Opcodes.DSUB,
                                Opcodes.DMUL,
                                Opcodes.DDIV,
                                Opcodes.DREM -> binaryOperation(2);
                case Opcodes.INEG, Opcodes.FNEG -> unaryOperation(1);
                case Opcodes.LNEG, Opcodes.DNEG -> unaryOperation(2);
                case Opcodes.I2F,
                                Opcodes.L2I,
                                Opcodes.L2F,
                                Opcodes.F2I,
                                Opcodes.D2I,
                                Opcodes.D2F,
                                Opcodes.I2B,
                                Opcodes.I2C,
                                Opcodes.I2S -> convert(1);
                case Opcodes.I2L,
                                Opcodes.I2D,
                                Opcodes.F2L,
                                Opcodes.F2D,
                                Opcodes.L2D,
                                Opcodes.D2L -> convert(2);
                case Opcodes.LCMP,
                                Opcodes.FCMPL,
                                Opcodes.FCMPG,
                                Opcodes.DCMPL,
                                Opcodes.DCMPG -> binaryOperation(1);
                case Opcodes.IRETURN,
                                Opcodes.FRETURN,
                                Opcodes.ARETURN,
                                Opcodes.ATHROW,
                                Opcodes.MONITORENTER,
                                Opcodes.MONITOREXIT -> pop(1);
                case Opcodes.LRETURN, Opcodes.DRETURN -> pop(2);
                case Opcodes.RETURN -> {
                    // No stack effect.
                }
                case Opcodes.ARRAYLENGTH -> {
                    pop(1);
                    push(DynamicStackValue.unresolved(1));
                }
                default -> invalidateProvenance();
            }
        }

        private DynamicTargetEvidence targetEvidence(
                String owner,
                String name,
                DynamicStackValue receiver,
                Type[] argumentTypes,
                DynamicStackValue[] arguments) {
            if (ambiguousControlFlow) {
                return DynamicTargetEvidence.ambiguousTarget();
            }
            if (isDynamicTargetInvocation(owner, name)) {
                int targetArgumentIndex = targetArgumentIndex(owner, argumentTypes);
                return targetArgumentIndex < arguments.length
                        ? arguments[targetArgumentIndex].targetEvidence()
                        : DynamicTargetEvidence.unresolved();
            }
            return receiver == null
                    ? DynamicTargetEvidence.unresolved()
                    : receiver.targetEvidence();
        }

        private static int targetArgumentIndex(
                String owner, Type[] argumentTypes) {
            if (owner.equals("java/lang/reflect/Proxy")) {
                return 1;
            }
            if (owner.equals("java/util/ServiceLoader")) {
                for (int index = 0; index < argumentTypes.length; index++) {
                    if (argumentTypes[index].getSort() == Type.OBJECT
                            && argumentTypes[index]
                                    .getClassName()
                                    .equals("java.lang.Class")) {
                        return index;
                    }
                }
            }
            return 0;
        }

        private void resolveDeferredInvocations(
                String owner,
                String name,
                DynamicStackValue receiver,
                DynamicStackValue[] arguments) {
            if (ambiguousControlFlow
                    || receiver == null
                    || !isDeferredServiceProviderConsumer(owner, name)) {
                return;
            }
            for (DynamicStackValue argument : arguments) {
                for (int invocationIndex : argument.deferredInvocationIndexes()) {
                    DynamicInvocationEvidence deferred =
                            invocations.get(invocationIndex);
                    if (!deferred.targetOwnerName()
                                    .equals("java.util.ServiceLoader$Provider")
                            || !deferred.targetName().equals("get")) {
                        continue;
                    }
                    DynamicTargetEvidence evidence = receiver.targetEvidence();
                    if (!resolvedDeferredInvocations.add(invocationIndex)) {
                        evidence = DynamicTargetEvidence.ambiguousTarget();
                    }
                    invocations.set(
                            invocationIndex,
                            deferred.withTargetEvidence(evidence));
                }
            }
        }

        private static boolean isDeferredServiceProviderConsumer(
                String owner, String name) {
            return (owner.equals("java/util/Optional")
                            && Set.of("filter", "flatMap", "map").contains(name))
                    || (owner.equals("java/util/stream/Stream")
                            && Set.of("filter", "flatMap", "map", "peek")
                                    .contains(name));
        }

        private static DynamicTargetEvidence carrierReturnEvidence(
                String owner, String name, DynamicStackValue receiver) {
            if (receiver == null) {
                return DynamicTargetEvidence.unresolved();
            }
            if (owner.equals("java/util/stream/Stream")
                    && Set.of(
                                    "filter",
                                    "findAny",
                                    "findFirst",
                                    "peek")
                            .contains(name)) {
                return receiver.targetEvidence();
            }
            if (owner.equals("java/util/Optional")
                    && Set.of(
                                    "filter",
                                    "get",
                                    "orElseThrow")
                            .contains(name)) {
                return receiver.targetEvidence();
            }
            return DynamicTargetEvidence.unresolved();
        }

        private void arrayLoad(int resultSize) {
            pop(1);
            pop(1);
            push(DynamicStackValue.unresolved(resultSize));
        }

        private void arrayStore(int valueSize) {
            pop(valueSize);
            pop(1);
            pop(1);
        }

        private void binaryOperation(int resultSize) {
            popAny();
            popAny();
            push(DynamicStackValue.unresolved(resultSize));
        }

        private void unaryOperation(int resultSize) {
            popAny();
            push(DynamicStackValue.unresolved(resultSize));
        }

        private void convert(int resultSize) {
            popAny();
            push(DynamicStackValue.unresolved(resultSize));
        }

        private void popTwoSlots() {
            DynamicStackValue top = popAny();
            if (top.size() == 1) {
                pop(1);
            }
        }

        private void duplicateTop() {
            DynamicStackValue top = pop(1);
            push(top);
            push(top);
        }

        private void duplicateTopUnderOne() {
            DynamicStackValue top = pop(1);
            DynamicStackValue below = pop(1);
            push(top);
            push(below);
            push(top);
        }

        private void duplicateTwoSlots() {
            DynamicStackValue top = popAny();
            if (top.size() == 2) {
                push(top);
                push(top);
                return;
            }
            DynamicStackValue below = pop(1);
            push(below);
            push(top);
            push(below);
            push(top);
        }

        private void swapTop() {
            DynamicStackValue top = pop(1);
            DynamicStackValue below = pop(1);
            push(top);
            push(below);
        }

        private DynamicStackValue pop(int expectedSize) {
            DynamicStackValue value = popAny();
            if (value.size() != expectedSize) {
                invalidateProvenance();
                return DynamicStackValue.ambiguous(expectedSize);
            }
            return value;
        }

        private DynamicStackValue popAny() {
            if (operandStack.isEmpty()) {
                invalidateProvenance();
                return DynamicStackValue.ambiguous(1);
            }
            return operandStack.removeLast();
        }

        private void push(DynamicStackValue value) {
            operandStack.add(value);
        }

        private void invalidateProvenance() {
            ambiguousControlFlow = true;
            operandStack.clear();
            locals.clear();
        }
    }

    private static ServiceDescriptorCatalog createAndScanServiceDescriptorFixtures(
            Path tempDirectory, String descriptorContent) throws IOException {
        String separator = File.pathSeparator;
        assertThat(
                        splitJavaClassPath(
                                separator
                                        + "alpha"
                                        + separator
                                        + separator
                                        + "omega"
                                        + separator))
                .containsExactly("", "alpha", "", "omega", "");
        assertThat(resolveClassPathEntry(""))
                .isEqualTo(Path.of("").toAbsolutePath().normalize());

        String descriptorEntry = SERVICES_PATH + "com.vendor.Plugin";
        byte[] descriptorBytes = descriptorContent.getBytes(StandardCharsets.UTF_8);

        Path directoryEntry = tempDirectory.resolve("ordinary-classes");
        writeDirectoryDescriptor(directoryEntry, descriptorBytes);

        Path uriDirectoryEntry = tempDirectory.resolve("uri-classes");
        writeDirectoryDescriptor(uriDirectoryEntry, descriptorBytes);

        Path extensionlessArchive = tempDirectory.resolve("UPPERCASE_ARCHIVE");
        writeArchive(extensionlessArchive, Map.of(descriptorEntry, descriptorBytes));

        Path uriArchive = tempDirectory.resolve("uri-archive.data");
        writeArchive(uriArchive, Map.of(descriptorEntry, descriptorBytes));

        Path bootClassesArchive = tempDirectory.resolve("boot-classes.bin");
        writeArchive(
                bootClassesArchive,
                Map.of(
                        BOOT_CLASSES_SERVICES_PATH + "com.vendor.Plugin",
                        descriptorBytes));

        byte[] nestedLibrary = archiveBytes(Map.of(descriptorEntry, descriptorBytes));
        Path nestedBootArchive = tempDirectory.resolve("nested-boot.bin");
        writeArchive(
                nestedBootArchive,
                Map.of(BOOT_LIB_PATH + "opaque-provider.library", nestedLibrary));

        Path manifestAppDirectory = tempDirectory.resolve("manifest-app");
        Path manifestLibraryDirectory = tempDirectory.resolve("manifest-libs");
        Path manifestSafeDirectory = tempDirectory.resolve("manifest-safe-classes");
        Files.createDirectories(manifestAppDirectory);
        Files.createDirectories(manifestLibraryDirectory);
        writeDirectoryDescriptor(
                manifestSafeDirectory,
                "com.vendor.ManifestSafeProvider".getBytes(StandardCharsets.UTF_8));
        Path manifestRootArchive = manifestAppDirectory.resolve("manifest-root.bin");
        Path manifestProviderArchive =
                manifestLibraryDirectory.resolve("formal-provider.bin");
        writeArchive(
                manifestProviderArchive,
                Map.of(
                        MANIFEST_PATH,
                        manifestBytes("../manifest-app/manifest-root.bin"),
                        descriptorEntry,
                        (FIXTURE_PACKAGE + ".ManifestOnlyFormalProvider")
                                .getBytes(StandardCharsets.UTF_8)));
        writeArchive(
                manifestRootArchive,
                Map.of(
                        MANIFEST_PATH,
                        manifestBytes(
                                "../manifest-libs/formal-provider.bin "
                                        + "../manifest-libs/./formal-provider.bin "
                                        + "../manifest-safe-classes/")));

        Path physicalOwnerDirectory = tempDirectory.resolve("manifest-alias-00-physical");
        Path hardlinkOwnerDirectory = tempDirectory.resolve("manifest-alias-10-hardlink");
        Path symlinkOwnerDirectory = tempDirectory.resolve("manifest-alias-20-symlink");
        Files.createDirectories(physicalOwnerDirectory);
        Files.createDirectories(hardlinkOwnerDirectory);
        Files.createDirectories(symlinkOwnerDirectory);
        Path physicalOwnerArchive = physicalOwnerDirectory.resolve("owner.bin");
        writeArchive(
                physicalOwnerArchive,
                Map.of(MANIFEST_PATH, manifestBytes("relative-provider.bin")));
        Path hardlinkOwnerArchive = hardlinkOwnerDirectory.resolve("owner.bin");
        Files.createLink(hardlinkOwnerArchive, physicalOwnerArchive);
        writeArchive(
                hardlinkOwnerDirectory.resolve("relative-provider.bin"),
                Map.of(
                        descriptorEntry,
                        (FIXTURE_PACKAGE + ".HardlinkManifestFormalProvider")
                                .getBytes(StandardCharsets.UTF_8)));
        Optional<Path> symlinkOwnerArchive =
                tryCreateSymbolicLink(
                        symlinkOwnerDirectory.resolve("owner.bin"), physicalOwnerArchive);
        if (symlinkOwnerArchive.isPresent()) {
            writeArchive(
                    symlinkOwnerDirectory.resolve("relative-provider.bin"),
                    Map.of(
                            descriptorEntry,
                            (FIXTURE_PACKAGE + ".SymlinkManifestFormalProvider")
                                    .getBytes(StandardCharsets.UTF_8)));
        }

        Path plainFile = tempDirectory.resolve("not-an-archive.txt");
        Files.writeString(plainFile, "not a ZIP archive", StandardCharsets.UTF_8);
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(plainFile.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a readable ZIP archive");

        Path brokenNestedArchive = tempDirectory.resolve("broken-nested.bin");
        writeArchive(
                brokenNestedArchive,
                Map.of(
                        BOOT_LIB_PATH + "broken.library",
                        localHeaderOnlyArchiveBytes(
                                Map.of(descriptorEntry, descriptorBytes))));
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(brokenNestedArchive.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid ZIP central directory");

        Path invalidUtf8Archive = tempDirectory.resolve("invalid-utf8.bin");
        writeArchive(
                invalidUtf8Archive,
                Map.of(descriptorEntry, new byte[] {(byte) 0xc3, 0x28}));
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(invalidUtf8Archive.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid UTF-8");

        List<String> fixtureClassPath =
                new ArrayList<>(
                        splitJavaClassPath(
                                separator
                                        + directoryEntry
                                        + separator
                                        + separator
                                        + extensionlessArchive
                                        + separator));
        fixtureClassPath.add(directoryEntry.toUri().toASCIIString());
        fixtureClassPath.add(uriDirectoryEntry.toUri().toASCIIString());
        fixtureClassPath.add(uriArchive.toUri().toASCIIString());
        fixtureClassPath.add(bootClassesArchive.toString());
        fixtureClassPath.add(nestedBootArchive.toString());
        fixtureClassPath.add(manifestRootArchive.toString());
        fixtureClassPath.add(physicalOwnerArchive.toString());
        fixtureClassPath.add(hardlinkOwnerArchive.toString());
        symlinkOwnerArchive.ifPresent(path -> fixtureClassPath.add(path.toString()));
        ServiceDescriptorCatalog catalog = scanClassPathEntries(fixtureClassPath);
        symlinkOwnerArchive.ifPresent(
                ignored ->
                        assertThat(catalog.ownedProviderNames())
                                .contains(FIXTURE_PACKAGE + ".SymlinkManifestFormalProvider"));
        assertThat(catalog.descriptors())
                .filteredOn(descriptor -> descriptor.source().contains(tempDirectory.toString()))
                .hasSize(symlinkOwnerArchive.isPresent() ? 10 : 9);
        assertScannerBudgets(tempDirectory);
        return catalog;
    }

    private static void writeDirectoryDescriptor(
            Path classesDirectory, byte[] content) throws IOException {
        Path descriptor =
                classesDirectory
                        .resolve("META-INF")
                        .resolve("services")
                        .resolve("com.vendor.Plugin");
        Files.createDirectories(descriptor.getParent());
        Files.write(descriptor, content);
    }

    private static void writeArchive(
            Path archive, Map<String, byte[]> entries) throws IOException {
        Files.write(archive, archiveBytes(entries));
    }

    private static Optional<Path> tryCreateSymbolicLink(Path link, Path target) {
        try {
            return Optional.of(Files.createSymbolicLink(link, target));
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            return Optional.empty();
        }
    }

    private static byte[] archiveBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry :
                    entries.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .toList()) {
                archive.putNextEntry(new ZipEntry(entry.getKey()));
                archive.write(entry.getValue());
                archive.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] manifestBytes(String classPath) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes()
                .put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes()
                .put(Attributes.Name.CLASS_PATH, classPath);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        manifest.write(bytes);
        return bytes.toByteArray();
    }

    private static byte[] localHeaderOnlyArchiveBytes(
            Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream archive = new ZipOutputStream(bytes);
        for (Map.Entry<String, byte[]> entry :
                entries.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .toList()) {
            archive.putNextEntry(new ZipEntry(entry.getKey()));
            archive.write(entry.getValue());
            archive.closeEntry();
        }
        archive.flush();
        byte[] localHeadersOnly = bytes.toByteArray();
        archive.close();
        return localHeadersOnly;
    }

    private static void assertScannerBudgets(Path tempDirectory) throws IOException {
        String descriptorEntry = SERVICES_PATH + "a.B";
        String exactDescriptor = "a.B" + " ".repeat(29);
        Path descriptorDirectory = tempDirectory.resolve("descriptor-budget");
        writeDirectoryDescriptor(
                descriptorDirectory,
                exactDescriptor.getBytes(StandardCharsets.UTF_8));

        ScanLimits descriptorBoundary = testScanLimits(32, 4_096, 1, 4, 4, 8_192);
        scanClassPathEntries(List.of(descriptorDirectory.toString()), descriptorBoundary);
        Files.writeString(
                descriptorDirectory
                        .resolve("META-INF")
                        .resolve("services")
                        .resolve("com.vendor.Plugin"),
                exactDescriptor + " ",
                StandardCharsets.UTF_8);
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(descriptorDirectory.toString()),
                                        descriptorBoundary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("descriptor byte limit");

        byte[] nestedDescriptor = "a.B".getBytes(StandardCharsets.UTF_8);
        byte[] nestedArchive =
                archiveBytes(Map.of(descriptorEntry, nestedDescriptor));
        Path outerArchive = tempDirectory.resolve("budget-outer.bin");
        writeArchive(
                outerArchive,
                Map.of(BOOT_LIB_PATH + "nested.library", nestedArchive));
        long exactExpandedBytes = nestedArchive.length + nestedDescriptor.length;
        ScanLimits exactNestedBoundary =
                testScanLimits(
                        nestedDescriptor.length,
                        nestedArchive.length,
                        1,
                        1,
                        2,
                        exactExpandedBytes);
        scanClassPathEntries(List.of(outerArchive.toString()), exactNestedBoundary);

        assertBudgetExceeded(
                outerArchive,
                testScanLimits(
                        nestedDescriptor.length,
                        nestedArchive.length - 1L,
                        1,
                        1,
                        2,
                        exactExpandedBytes),
                "nested archive byte limit");
        assertBudgetExceeded(
                outerArchive,
                testScanLimits(
                        nestedDescriptor.length,
                        nestedArchive.length,
                        0,
                        1,
                        2,
                        exactExpandedBytes),
                "nested archive count limit");
        assertBudgetExceeded(
                outerArchive,
                testScanLimits(
                        nestedDescriptor.length,
                        nestedArchive.length,
                        1,
                        0,
                        2,
                        exactExpandedBytes),
                "nested archive entry limit");
        assertBudgetExceeded(
                outerArchive,
                testScanLimits(
                        nestedDescriptor.length,
                        nestedArchive.length,
                        1,
                        1,
                        1,
                        exactExpandedBytes),
                "relevant entry limit");
        assertBudgetExceeded(
                outerArchive,
                testScanLimits(
                        nestedDescriptor.length,
                        nestedArchive.length,
                        1,
                        1,
                        2,
                        exactExpandedBytes - 1L),
                "total relevant expanded byte limit");
        assertRawArchivePreflight(tempDirectory);
        assertManifestClassPathTraversal(tempDirectory);
        assertCleanupFailurePolicy(tempDirectory);
    }

    private static void assertManifestClassPathTraversal(Path tempDirectory) throws IOException {
        Path fixtureDirectory = tempDirectory.resolve("manifest-traversal-budget");
        Files.createDirectories(fixtureDirectory);
        Path dependency = fixtureDirectory.resolve("dependency.bin");
        writeArchive(dependency, Map.of());
        Path root = fixtureDirectory.resolve("root.bin");
        byte[] rootManifest = manifestBytes("dependency.bin");
        writeArchive(root, Map.of(MANIFEST_PATH, rootManifest));

        scanClassPathEntries(
                List.of(root.toString()),
                withManifestTraversalLimits(
                        testScanLimits(256, 4_096, 1, 10, 10, 8_192),
                        rootManifest.length,
                        2,
                        2,
                        1));

        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(root.toString()),
                                        withManifestTraversalLimits(
                                                testScanLimits(
                                                        256, 4_096, 1, 10, 10, 8_192),
                                                rootManifest.length,
                                                1,
                                                2,
                                                1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolved classpath entry limit");
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(root.toString()),
                                        withManifestTraversalLimits(
                                                testScanLimits(
                                                        256, 4_096, 1, 10, 10, 8_192),
                                                rootManifest.length,
                                                2,
                                                1,
                                                1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classpath archive count limit");
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(root.toString()),
                                        withManifestTraversalLimits(
                                                testScanLimits(
                                                        256, 4_096, 1, 10, 10, 8_192),
                                                rootManifest.length,
                                                2,
                                                2,
                                                0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifest Class-Path hop depth limit");
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(root.toString()),
                                        withManifestTraversalLimits(
                                                testScanLimits(
                                                        256, 4_096, 1, 10, 10, 8_192),
                                                rootManifest.length - 1L,
                                                2,
                                                2,
                                                1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifest byte limit");

        Path unsupportedUri = fixtureDirectory.resolve("unsupported-uri.bin");
        writeArchive(
                unsupportedUri,
                Map.of(MANIFEST_PATH, manifestBytes("https://example.invalid/provider.jar")));
        assertManifestTraversalFails(unsupportedUri, "unsupported URI scheme");

        Path malformedUri = fixtureDirectory.resolve("malformed-uri.bin");
        writeArchive(malformedUri, Map.of(MANIFEST_PATH, manifestBytes("bad[uri")));
        assertManifestTraversalFails(malformedUri, "malformed Class-Path URI");

        Path staleThirdPartyManifest = fixtureDirectory.resolve("stale-third-party-manifest.bin");
        writeArchive(
                staleThirdPartyManifest,
                Map.of(MANIFEST_PATH, manifestBytes("missing-transitive-dependency.jar")));
        assertThat(
                        scanClassPathEntries(
                                        List.of(staleThirdPartyManifest.toString()),
                                        testScanLimits(256, 4_096, 1, 10, 10, 8_192))
                                .descriptors())
                .isEmpty();

        Path malformedManifest = fixtureDirectory.resolve("malformed-manifest.bin");
        writeArchive(
                malformedManifest,
                Map.of(
                        MANIFEST_PATH,
                        "Manifest-Version: 1.0\r\nMalformed Header\r\n\r\n"
                                .getBytes(StandardCharsets.UTF_8)));
        assertManifestTraversalFails(malformedManifest, "malformed archive manifest");
    }

    private static void assertManifestTraversalFails(Path archive, String message) {
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(archive.toString()),
                                        testScanLimits(256, 4_096, 1, 10, 10, 8_192)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }

    private static void assertRawArchivePreflight(Path tempDirectory) throws IOException {
        Path zip64Sentinel = tempDirectory.resolve("zip64-sentinel.bin");
        Files.write(zip64Sentinel, fakeEocdArchive(0xffff, 0xffffffffL, 0xffffffffL));
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(zip64Sentinel.toString()),
                                        testScanLimits(64, 4_096, 1, 10, 10, 8_192)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ZIP64 archives are unsupported");

        Path forgedEntryCount = tempDirectory.resolve("forged-entry-count.bin");
        Files.write(forgedEntryCount, fakeEocdArchive(100, 0, 0));
        ScanLimits tenArchiveEntries =
                withArchivePreflightLimits(
                        testScanLimits(64, 4_096, 1, 10, 10, 8_192),
                        1_048_576,
                        10,
                        1_048_576,
                        10);
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(forgedEntryCount.toString()),
                                        tenArchiveEntries))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("total archive entry limit");

        Path forgedCentralSize = tempDirectory.resolve("forged-central-size.bin");
        Files.write(forgedCentralSize, fakeEocdArchive(0, 100, 0));
        ScanLimits tenCentralBytes =
                withArchivePreflightLimits(
                        testScanLimits(64, 4_096, 1, 10, 10, 8_192),
                        1_048_576,
                        10,
                        10,
                        1_000);
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(forgedCentralSize.toString()),
                                        tenCentralBytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("central directory byte limit");

        byte[] validArchive =
                archiveBytes(
                        Map.of(
                                SERVICES_PATH + "a.B",
                                "a.B".getBytes(StandardCharsets.UTF_8)));
        Path localNameMismatch = tempDirectory.resolve("local-name-mismatch.bin");
        byte[] mismatchedArchive = validArchive.clone();
        mismatchedArchive[30] ^= 1;
        Files.write(localNameMismatch, mismatchedArchive);
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(localNameMismatch.toString()),
                                        testScanLimits(64, 4_096, 1, 10, 10, 8_192)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local and central raw entry names differ");

        Path firstArchive = tempDirectory.resolve("archive-count-first.bin");
        Path secondArchive = tempDirectory.resolve("archive-count-second.bin");
        Files.write(firstArchive, validArchive);
        Files.write(secondArchive, validArchive);
        ScanLimits oneClasspathArchive =
                withArchivePreflightLimits(
                        testScanLimits(64, 4_096, 1, 10, 10, 8_192),
                        1_048_576,
                        1,
                        1_048_576,
                        1_000);
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(
                                                firstArchive.toString(),
                                                secondArchive.toString()),
                                        oneClasspathArchive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classpath archive count limit");

        ScanLimits smallerThanArchive =
                withArchivePreflightLimits(
                        testScanLimits(64, 4_096, 1, 10, 10, 8_192),
                        validArchive.length - 1L,
                        10,
                        1_048_576,
                        1_000);
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(firstArchive.toString()),
                                        smallerThanArchive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archive file byte limit");
    }

    private static byte[] fakeEocdArchive(
            int entryCount, long centralDirectoryBytes, long centralDirectoryOffset) {
        int prefixBytes =
                centralDirectoryBytes <= 1_024
                        ? Math.toIntExact(centralDirectoryBytes)
                        : 0;
        byte[] archive = new byte[prefixBytes + 22];
        int eocd = prefixBytes;
        putUnsignedInt(archive, eocd, EOCD_SIGNATURE);
        putUnsignedShort(archive, eocd + 8, entryCount);
        putUnsignedShort(archive, eocd + 10, entryCount);
        putUnsignedInt(archive, eocd + 12, centralDirectoryBytes);
        putUnsignedInt(archive, eocd + 16, centralDirectoryOffset);
        return archive;
    }

    private static void assertCleanupFailurePolicy(Path tempDirectory) throws IOException {
        Path stagedArchive = tempDirectory.resolve("cleanup-policy.tmp");
        Files.writeString(stagedArchive, "fixture", StandardCharsets.UTF_8);
        IllegalStateException primaryFailure =
                new IllegalStateException("primary scan failure");
        cleanupTemporaryArchive(
                stagedArchive,
                primaryFailure,
                ignored -> {
                    throw new IOException("synthetic cleanup failure");
                });
        assertThat(primaryFailure.getSuppressed()).hasSize(1);
        assertThat(primaryFailure.getSuppressed()[0])
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("cannot delete nested archive staging file");
        assertThatThrownBy(
                        () ->
                                cleanupTemporaryArchive(
                                        stagedArchive,
                                        null,
                                        ignored -> {
                                            throw new IOException(
                                                    "synthetic cleanup failure");
                                        }))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("cannot delete nested archive staging file");

        SecurityException securityFailure =
                new SecurityException("synthetic security cleanup failure");
        IllegalStateException securityPrimaryFailure =
                new IllegalStateException("primary scan failure before security cleanup");
        cleanupTemporaryArchive(
                stagedArchive,
                securityPrimaryFailure,
                ignored -> {
                    throw securityFailure;
                });
        assertThat(securityPrimaryFailure.getSuppressed())
                .containsExactly(securityFailure);
        assertThatThrownBy(
                        () ->
                                cleanupTemporaryArchive(
                                        stagedArchive,
                                        null,
                                        ignored -> {
                                            throw securityFailure;
                                        }))
                .isSameAs(securityFailure);
        Files.deleteIfExists(stagedArchive);
    }

    private static ScanLimits testScanLimits(
            long maxDescriptorBytes,
            long maxNestedArchiveBytes,
            long maxNestedArchiveCount,
            long maxNestedArchiveEntries,
            long maxRelevantEntries,
            long maxTotalRelevantExpandedBytes) {
        return new ScanLimits(
                maxDescriptorBytes,
                1_048_576,
                maxNestedArchiveBytes,
                1_048_576,
                1_000,
                100,
                32,
                1_048_576,
                1_000,
                maxNestedArchiveCount,
                maxNestedArchiveEntries,
                maxRelevantEntries,
                maxTotalRelevantExpandedBytes);
    }

    private static ScanLimits withArchivePreflightLimits(
            ScanLimits limits,
            long maxArchiveFileBytes,
            long maxClasspathArchiveCount,
            long maxCentralDirectoryBytes,
            long maxTotalArchiveEntries) {
        return new ScanLimits(
                limits.maxDescriptorBytes(),
                limits.maxManifestBytes(),
                limits.maxNestedArchiveBytes(),
                maxArchiveFileBytes,
                limits.maxResolvedClassPathEntries(),
                maxClasspathArchiveCount,
                limits.maxManifestHopDepth(),
                maxCentralDirectoryBytes,
                maxTotalArchiveEntries,
                limits.maxNestedArchiveCount(),
                limits.maxNestedArchiveEntries(),
                limits.maxRelevantEntries(),
                limits.maxTotalRelevantExpandedBytes());
    }

    private static ScanLimits withManifestTraversalLimits(
            ScanLimits limits,
            long maxManifestBytes,
            long maxResolvedClassPathEntries,
            long maxClasspathArchiveCount,
            long maxManifestHopDepth) {
        return new ScanLimits(
                limits.maxDescriptorBytes(),
                maxManifestBytes,
                limits.maxNestedArchiveBytes(),
                limits.maxArchiveFileBytes(),
                maxResolvedClassPathEntries,
                maxClasspathArchiveCount,
                maxManifestHopDepth,
                limits.maxCentralDirectoryBytes(),
                limits.maxTotalArchiveEntries(),
                limits.maxNestedArchiveCount(),
                limits.maxNestedArchiveEntries(),
                limits.maxRelevantEntries(),
                limits.maxTotalRelevantExpandedBytes());
    }

    private static void assertBudgetExceeded(
            Path classPathEntry, ScanLimits limits, String message) {
        assertThatThrownBy(
                        () ->
                                scanClassPathEntries(
                                        List.of(classPathEntry.toString()), limits))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }

    private static ServiceDescriptorCatalog scanProductionServiceDescriptors() {
        return scanClassPathEntries(
                splitJavaClassPath(System.getProperty("java.class.path", "")));
    }

    private static List<String> splitJavaClassPath(String classPath) {
        return Arrays.asList(
                classPath.split(Pattern.quote(File.pathSeparator), -1));
    }

    private static ServiceDescriptorCatalog scanClassPathEntries(
            List<String> rawEntries) {
        return scanClassPathEntries(rawEntries, PRODUCTION_SCAN_LIMITS);
    }

    private static ServiceDescriptorCatalog scanClassPathEntries(
            List<String> rawEntries, ScanLimits limits) {
        ScanBudget budget = new ScanBudget(limits);
        ArrayDeque<ClassPathScanEntry> pending =
                rawEntries.stream()
                        .map(
                                rawEntry ->
                                        new ClassPathScanEntry(
                                                resolveClassPathEntry(rawEntry),
                                                0,
                                                "classpath entry '" + rawEntry + "'"))
                        .peek(entry -> budget.recordResolvedClassPathEntry(entry.source()))
                        .sorted(Comparator.comparing(entry -> entry.path().toString()))
                        .collect(Collectors.toCollection(ArrayDeque::new));
        Set<ClassPathTraversalKey> visitedEntries = new HashSet<>();
        List<ServiceDescriptor> descriptors = new ArrayList<>();
        while (!pending.isEmpty()) {
            ClassPathScanEntry candidate = pending.removeFirst();
            if (!Files.exists(candidate.path()) && candidate.manifestHopDepth() > 0) {
                // URLClassLoader ignores unreachable manifest URLs; they still consume scan width.
                continue;
            }
            CanonicalClassPathEntry canonical =
                    canonicalClassPathEntry(candidate.path(), candidate.source());
            if (!visitedEntries.add(canonical.traversalKey())) {
                continue;
            }
            budget.checkManifestHopDepth(
                    candidate.manifestHopDepth(), candidate.source());
            scanClassPathEntry(
                            canonical.path(),
                            candidate.manifestHopDepth(),
                            descriptors,
                            budget)
                    .stream()
                    .sorted(Comparator.comparing(entry -> entry.path().toString()))
                    .forEach(pending::addLast);
        }
        return new ServiceDescriptorCatalog(descriptors);
    }

    private static Path resolveClassPathEntry(String rawEntry) {
        try {
            Path path =
                    rawEntry.regionMatches(true, 0, "file:", 0, "file:".length())
                            ? Path.of(URI.create(rawEntry))
                            : Path.of(rawEntry);
            return path.toAbsolutePath().normalize();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "invalid classpath entry '" + rawEntry + "'", failure);
        }
    }

    private static CanonicalClassPathEntry canonicalClassPathEntry(
            Path classPathEntry, String source) {
        if (!Files.exists(classPathEntry)) {
            throw new IllegalStateException(
                    "classpath entry does not exist: " + classPathEntry + " from " + source);
        }
        if (!Files.isReadable(classPathEntry)) {
            throw new IllegalStateException(
                    "classpath entry is not readable: " + classPathEntry + " from " + source);
        }
        try {
            Path lexicalPath = classPathEntry.toAbsolutePath().normalize();
            Path canonicalPath = lexicalPath.toRealPath();
            BasicFileAttributes attributes =
                    Files.readAttributes(canonicalPath, BasicFileAttributes.class);
            Object fileKey = attributes.fileKey();
            String normalizedPath = canonicalPath.toString();
            if (File.separatorChar == '\\') {
                normalizedPath = normalizedPath.toLowerCase(Locale.ROOT);
            }
            String identity =
                    fileKey == null
                            ? "path:" + normalizedPath
                            : "file:" + canonicalPath.getRoot() + ":" + fileKey;
            Path manifestBase =
                    Files.isDirectory(lexicalPath) ? lexicalPath : lexicalPath.getParent();
            if (manifestBase == null) {
                throw new IllegalStateException(
                        "classpath entry has no lexical manifest base: " + lexicalPath);
            }
            return new CanonicalClassPathEntry(
                    lexicalPath,
                    new ClassPathTraversalKey(
                            identity, manifestBase.toUri().normalize().toASCIIString()));
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "cannot resolve canonical classpath entry "
                            + classPathEntry
                            + " from "
                            + source,
                    failure);
        }
    }

    private static List<ClassPathScanEntry> scanClassPathEntry(
            Path classPathEntry,
            int manifestHopDepth,
            List<ServiceDescriptor> descriptors,
            ScanBudget budget) {
        if (Files.isDirectory(classPathEntry)) {
            scanServiceDescriptorDirectory(classPathEntry, descriptors, budget);
            return List.of();
        }
        if (!Files.isRegularFile(classPathEntry)) {
            throw new IllegalStateException(
                    "classpath entry is neither a directory nor a regular archive: "
                            + classPathEntry);
        }
        return scanServiceDescriptorArchive(
                classPathEntry, manifestHopDepth, descriptors, budget);
    }

    private static void scanServiceDescriptorDirectory(
            Path classesDirectory,
            List<ServiceDescriptor> descriptors,
            ScanBudget budget) {
        Path serviceRoot = classesDirectory.resolve("META-INF").resolve("services");
        if (!Files.isDirectory(serviceRoot)) {
            return;
        }
        try (var paths = Files.list(serviceRoot)) {
            for (Path descriptor :
                    paths.filter(Files::isRegularFile)
                            .peek(path -> budget.recordRelevantEntry(path.toString()))
                            .sorted(Comparator.comparing(Path::toString))
                            .toList()) {
                descriptors.add(
                        parseServiceDescriptorBytes(
                                descriptor.toString(),
                                descriptor.getFileName().toString(),
                                readRelevantEntry(
                                        Files.newInputStream(descriptor),
                                        budget.descriptorReadBudget(),
                                        descriptor.toString(),
                                        budget::recordDescriptorBytes)));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "cannot scan production service descriptors under " + serviceRoot,
                    failure);
        }
    }

    private static List<ClassPathScanEntry> scanServiceDescriptorArchive(
            Path archivePath,
            int manifestHopDepth,
            List<ServiceDescriptor> descriptors,
            ScanBudget budget) {
        try {
            String source = archivePath.toString();
            budget.reserveClasspathArchive(Files.size(archivePath), source);
            ZipPreflight preflight =
                    preflightArchive(archivePath, source, 0, budget);
            try (JarFile archive = new JarFile(archivePath.toFile())) {
                return scanArchiveEntries(
                        archive,
                        source,
                        preflight,
                        Optional.of(
                                new ManifestScanContext(
                                        archivePath, manifestHopDepth)),
                        descriptors,
                        budget);
            }
        } catch (ZipException failure) {
            throw new IllegalStateException(
                    "classpath entry is not a readable ZIP archive: " + archivePath,
                    failure);
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "cannot scan production service descriptors in " + archivePath,
                    failure);
        }
    }

    private static ZipPreflight preflightArchive(
            Path archivePath,
            String source,
            int nestedDepth,
            ScanBudget budget) throws IOException {
        long archiveSize = Files.size(archivePath);
        budget.checkArchiveFileBytes(archiveSize, source);
        try (FileChannel archive = FileChannel.open(archivePath)) {
            EndOfCentralDirectory eocd =
                    readEndOfCentralDirectory(archive, archiveSize, source);
            rejectZip64Locator(archive, eocd.offset(), source);
            budget.recordCentralDirectoryBytes(eocd.centralDirectoryBytes(), source);
            budget.recordArchiveEntries(
                    eocd.entryCount(), nestedDepth > 0, source);

            if (eocd.centralDirectoryBytes() > eocd.offset()
                    || eocd.centralDirectoryOffset()
                            != eocd.offset() - eocd.centralDirectoryBytes()) {
                throw new IllegalStateException(
                        "central directory offset/size is inconsistent in " + source);
            }

            byte[] centralDirectory =
                    readFileRange(
                            archive,
                            eocd.centralDirectoryOffset(),
                            Math.toIntExact(eocd.centralDirectoryBytes()),
                            source);
            List<String> entryNames =
                    validateCentralDirectory(
                            archive,
                            centralDirectory,
                            eocd,
                            source);
            entryNames.sort(String::compareTo);
            return new ZipPreflight(List.copyOf(entryNames), nestedDepth);
        }
    }

    private static EndOfCentralDirectory readEndOfCentralDirectory(
            FileChannel archive, long archiveSize, String source) throws IOException {
        if (archiveSize < 22) {
            throw new ZipException(
                    "archive has no complete ZIP end-of-central-directory record: "
                            + source);
        }
        int tailLength = (int) Math.min(archiveSize, MAX_EOCD_BYTES);
        long tailOffset = archiveSize - tailLength;
        byte[] tail = readFileRange(archive, tailOffset, tailLength, source);
        for (int cursor = tail.length - 22; cursor >= 0; cursor--) {
            if (unsignedInt(tail, cursor) != EOCD_SIGNATURE) {
                continue;
            }
            int commentLength = unsignedShort(tail, cursor + 20);
            long eocdOffset = tailOffset + cursor;
            if (eocdOffset + 22L + commentLength != archiveSize) {
                continue;
            }
            int diskNumber = unsignedShort(tail, cursor + 4);
            int centralDirectoryDisk = unsignedShort(tail, cursor + 6);
            int entriesOnDisk = unsignedShort(tail, cursor + 8);
            int entryCount = unsignedShort(tail, cursor + 10);
            long centralDirectoryBytes = unsignedInt(tail, cursor + 12);
            long centralDirectoryOffset = unsignedInt(tail, cursor + 16);
            if (entriesOnDisk == 0xffff
                    || entryCount == 0xffff
                    || centralDirectoryBytes == 0xffffffffL
                    || centralDirectoryOffset == 0xffffffffL) {
                throw new IllegalStateException(
                        "ZIP64 archives are unsupported by the bounded preflight: "
                                + source);
            }
            if (diskNumber != 0
                    || centralDirectoryDisk != 0
                    || entriesOnDisk != entryCount) {
                throw new IllegalStateException(
                        "multi-disk ZIP archives are unsupported: " + source);
            }
            return new EndOfCentralDirectory(
                    eocdOffset,
                    centralDirectoryOffset,
                    centralDirectoryBytes,
                    entryCount);
        }
        throw new ZipException(
                "archive has no valid ZIP end-of-central-directory record: "
                        + source);
    }

    private static void rejectZip64Locator(
            FileChannel archive, long eocdOffset, String source) throws IOException {
        if (eocdOffset < 20) {
            return;
        }
        byte[] possibleLocator =
                readFileRange(archive, eocdOffset - 20, 4, source);
        if (unsignedInt(possibleLocator, 0) == ZIP64_EOCD_LOCATOR_SIGNATURE) {
            throw new IllegalStateException(
                    "ZIP64 archives are unsupported by the bounded preflight: "
                            + source);
        }
    }

    private static List<String> validateCentralDirectory(
            FileChannel archive,
            byte[] centralDirectory,
            EndOfCentralDirectory eocd,
            String source) throws IOException {
        List<String> entryNames = new ArrayList<>();
        int cursor = 0;
        for (int index = 0; index < eocd.entryCount(); index++) {
            if (centralDirectory.length - cursor < 46
                    || unsignedInt(centralDirectory, cursor)
                            != CENTRAL_FILE_HEADER_SIGNATURE) {
                throw new IllegalStateException(
                        "invalid central directory entry " + index + " in " + source);
            }
            long compressedBytes = unsignedInt(centralDirectory, cursor + 20);
            long uncompressedBytes = unsignedInt(centralDirectory, cursor + 24);
            int fileNameBytes = unsignedShort(centralDirectory, cursor + 28);
            int extraBytes = unsignedShort(centralDirectory, cursor + 30);
            int commentBytes = unsignedShort(centralDirectory, cursor + 32);
            int startDisk = unsignedShort(centralDirectory, cursor + 34);
            long localHeaderOffset = unsignedInt(centralDirectory, cursor + 42);
            if (compressedBytes == 0xffffffffL
                    || uncompressedBytes == 0xffffffffL
                    || startDisk == 0xffff
                    || localHeaderOffset == 0xffffffffL) {
                throw new IllegalStateException(
                        "ZIP64 central entry is unsupported in " + source);
            }
            if (startDisk != 0 || fileNameBytes == 0) {
                throw new IllegalStateException(
                        "invalid central directory entry metadata in " + source);
            }
            long entryBytes = 46L + fileNameBytes + extraBytes + commentBytes;
            if (entryBytes > centralDirectory.length - cursor) {
                throw new IllegalStateException(
                        "truncated central directory entry in " + source);
            }
            int fileNameOffset = cursor + 46;
            byte[] rawFileName =
                    Arrays.copyOfRange(
                            centralDirectory,
                            fileNameOffset,
                            fileNameOffset + fileNameBytes);
            validateNoZip64ExtraField(
                    centralDirectory,
                    fileNameOffset + fileNameBytes,
                    extraBytes,
                    source);
            validateLocalHeader(
                    archive,
                    localHeaderOffset,
                    rawFileName,
                    eocd.centralDirectoryOffset(),
                    source);
            entryNames.add(decodeZipEntryName(rawFileName, source));
            cursor = Math.toIntExact(cursor + entryBytes);
        }
        if (cursor != centralDirectory.length) {
            throw new IllegalStateException(
                    "central directory size/count is inconsistent in " + source);
        }
        return entryNames;
    }

    private static void validateNoZip64ExtraField(
            byte[] centralDirectory,
            int extraOffset,
            int extraLength,
            String source) {
        int cursor = extraOffset;
        int end = extraOffset + extraLength;
        while (cursor < end) {
            if (end - cursor < 4) {
                throw new IllegalStateException(
                        "truncated central directory extra field in " + source);
            }
            int headerId = unsignedShort(centralDirectory, cursor);
            int valueLength = unsignedShort(centralDirectory, cursor + 2);
            cursor += 4;
            if (valueLength > end - cursor) {
                throw new IllegalStateException(
                        "truncated central directory extra value in " + source);
            }
            if (headerId == 0x0001) {
                throw new IllegalStateException(
                        "ZIP64 central entry is unsupported in " + source);
            }
            cursor += valueLength;
        }
    }

    private static void validateLocalHeader(
            FileChannel archive,
            long localHeaderOffset,
            byte[] centralFileName,
            long centralDirectoryOffset,
            String source) throws IOException {
        if (localHeaderOffset > centralDirectoryOffset - 30) {
            throw new IllegalStateException(
                    "local header offset points outside file data in " + source);
        }
        byte[] localHeader =
                readFileRange(archive, localHeaderOffset, 30, source);
        if (unsignedInt(localHeader, 0) != LOCAL_FILE_HEADER_SIGNATURE) {
            throw new IllegalStateException(
                    "central entry has no matching local header signature in "
                            + source);
        }
        int localFileNameBytes = unsignedShort(localHeader, 26);
        int localExtraBytes = unsignedShort(localHeader, 28);
        if (localHeaderOffset + 30L + localFileNameBytes + localExtraBytes
                > centralDirectoryOffset) {
            throw new IllegalStateException(
                    "local header overlaps the central directory in " + source);
        }
        byte[] localFileName =
                readFileRange(
                        archive,
                        localHeaderOffset + 30,
                        localFileNameBytes,
                        source);
        if (!Arrays.equals(localFileName, centralFileName)) {
            throw new IllegalStateException(
                    "local and central raw entry names differ in " + source);
        }
    }

    private static String decodeZipEntryName(byte[] rawName, String source) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawName))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalStateException(
                    "ZIP entry name is not valid UTF-8 in " + source,
                    failure);
        }
    }

    private static byte[] readFileRange(
            FileChannel file,
            long offset,
            int length,
            String source) throws IOException {
        if (offset < 0 || length < 0 || offset > file.size() - length) {
            throw new IllegalStateException(
                    "ZIP structure points outside archive bounds in " + source);
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int read = file.read(buffer, offset + buffer.position());
            if (read < 0) {
                throw new IllegalStateException(
                        "truncated ZIP structure in " + source);
            }
        }
        return buffer.array();
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static void putUnsignedShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private static void putUnsignedInt(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static List<ClassPathScanEntry> scanArchiveEntries(
            JarFile archive,
            String source,
            ZipPreflight preflight,
            Optional<ManifestScanContext> manifestContext,
            List<ServiceDescriptor> descriptors,
            ScanBudget budget) throws IOException {
        List<JarEntry> relevantEntries = new ArrayList<>();
        List<String> jarEntryNames = new ArrayList<>();
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            jarEntryNames.add(entry.getName());
            if (!entry.isDirectory()
                    && (descriptorContract(entry.getName()).isPresent()
                            || isBootNestedArchive(entry.getName())
                            || (manifestContext.isPresent()
                                    && entry.getName().equals(MANIFEST_PATH)))) {
                budget.recordRelevantEntry(source + "!/" + entry.getName());
                relevantEntries.add(entry);
            }
        }
        jarEntryNames.sort(String::compareTo);
        if (!jarEntryNames.equals(preflight.sortedEntryNames())) {
            throw new IllegalStateException(
                    "JarFile entry names differ from raw central directory preflight: "
                            + source);
        }
        relevantEntries.sort(Comparator.comparing(JarEntry::getName));
        long manifestCount =
                relevantEntries.stream()
                        .filter(entry -> entry.getName().equals(MANIFEST_PATH))
                        .count();
        if (manifestCount > 1) {
            throw new IllegalStateException(
                    "archive contains duplicate manifests: " + source);
        }

        List<ClassPathScanEntry> manifestTargets = new ArrayList<>();
        for (JarEntry entry : relevantEntries) {
            String entrySource = source + "!/" + entry.getName();
            Optional<String> contract = descriptorContract(entry.getName());
            if (contract.isPresent()) {
                descriptors.add(
                        parseServiceDescriptorBytes(
                                entrySource,
                                contract.orElseThrow(),
                                readRelevantEntry(
                                        archive.getInputStream(entry),
                                        budget.descriptorReadBudget(),
                                        entrySource,
                                        budget::recordDescriptorBytes)));
            } else if (isBootNestedArchive(entry.getName())) {
                budget.reserveNestedArchive(entrySource);
                byte[] nestedArchive =
                        readRelevantEntry(
                                archive.getInputStream(entry),
                                        budget.nestedArchiveReadBudget(),
                                        entrySource,
                                        budget::recordNestedArchiveBytes);
                scanNestedArchive(
                        nestedArchive,
                        entrySource,
                        preflight.nestedDepth() + 1,
                        descriptors,
                        budget);
            } else {
                ManifestScanContext context = manifestContext.orElseThrow();
                byte[] manifestBytes =
                        readRelevantEntry(
                                archive.getInputStream(entry),
                                budget.manifestReadBudget(),
                                entrySource,
                                budget::recordManifestBytes);
                manifestTargets.addAll(
                        parseManifestClassPath(
                                manifestBytes,
                                context,
                                entrySource,
                                budget));
            }
        }
        return List.copyOf(manifestTargets);
    }

    private static List<ClassPathScanEntry> parseManifestClassPath(
            byte[] content,
            ManifestScanContext context,
            String source,
            ScanBudget budget) {
        Manifest manifest;
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            manifest = new Manifest(input);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(
                    "malformed archive manifest " + source,
                    failure);
        }
        String classPath =
                manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        if (classPath == null || classPath.isBlank()) {
            return List.of();
        }
        List<ClassPathScanEntry> targets = new ArrayList<>();
        StringTokenizer tokens = new StringTokenizer(classPath);
        while (tokens.hasMoreTokens()) {
            String token = tokens.nextToken();
            String targetSource = source + " Class-Path '" + token + "'";
            budget.recordResolvedClassPathEntry(targetSource);
            Path target = resolveManifestClassPathTarget(context.ownerArchive(), token, source);
            targets.add(
                    new ClassPathScanEntry(
                            target,
                            context.manifestHopDepth() + 1,
                            targetSource));
        }
        return List.copyOf(targets);
    }

    private static Path resolveManifestClassPathTarget(
            Path ownerArchive, String token, String source) {
        URI tokenUri;
        try {
            tokenUri = new URI(token);
        } catch (URISyntaxException failure) {
            throw new IllegalStateException(
                    "malformed Class-Path URI '" + token + "' in " + source,
                    failure);
        }
        if (tokenUri.isAbsolute()
                && !"file".equalsIgnoreCase(tokenUri.getScheme())) {
            throw new IllegalStateException(
                    "unsupported URI scheme in manifest Class-Path '"
                            + token
                            + "' in "
                            + source);
        }
        Path ownerParent = ownerArchive.getParent();
        if (ownerParent == null) {
            throw new IllegalStateException(
                    "owning archive has no filesystem parent for manifest Class-Path: "
                            + ownerArchive);
        }
        URI resolved =
                tokenUri.isAbsolute()
                        ? tokenUri
                        : ownerParent.toUri().resolve(tokenUri);
        if (!"file".equalsIgnoreCase(resolved.getScheme())
                || resolved.getQuery() != null
                || resolved.getFragment() != null) {
            throw new IllegalStateException(
                    "unsupported file URI in manifest Class-Path '"
                            + token
                            + "' in "
                            + source);
        }
        try {
            return Path.of(resolved).toAbsolutePath().normalize();
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "malformed Class-Path URI '" + token + "' in " + source,
                    failure);
        }
    }

    private static void scanNestedArchive(
            byte[] archiveBytes,
            String source,
            int depth,
            List<ServiceDescriptor> descriptors,
            ScanBudget budget) {
        if (depth > MAX_NESTED_ARCHIVE_DEPTH) {
            throw new IllegalStateException(
                    "nested BOOT-INF/lib archive depth exceeds "
                            + MAX_NESTED_ARCHIVE_DEPTH
                            + ": "
                            + source);
        }
        Path temporaryArchive = null;
        Throwable primaryFailure = null;
        try {
            temporaryArchive = Files.createTempFile("intake-service-provider-", ".zip");
            Files.write(temporaryArchive, archiveBytes);
            ZipPreflight preflight =
                    preflightArchive(
                            temporaryArchive, source, depth, budget);
            try (JarFile archive = new JarFile(temporaryArchive.toFile())) {
                scanArchiveEntries(
                        archive,
                        source,
                        preflight,
                        Optional.empty(),
                        descriptors,
                        budget);
            }
        } catch (ZipException failure) {
            IllegalStateException wrapped = new IllegalStateException(
                    "BOOT-INF/lib entry has no valid ZIP central directory: " + source,
                    failure);
            primaryFailure = wrapped;
            throw wrapped;
        } catch (IOException failure) {
            UncheckedIOException wrapped = new UncheckedIOException(
                    "cannot scan nested BOOT-INF/lib archive " + source,
                    failure);
            primaryFailure = wrapped;
            throw wrapped;
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (temporaryArchive != null) {
                cleanupTemporaryArchive(
                        temporaryArchive,
                        primaryFailure,
                        Files::deleteIfExists);
            }
        }
    }

    private static void cleanupTemporaryArchive(
            Path temporaryArchive,
            Throwable primaryFailure,
            TemporaryArchiveDeleter deleter) {
        try {
            deleter.delete(temporaryArchive);
        } catch (IOException failure) {
            UncheckedIOException cleanupFailure =
                    new UncheckedIOException(
                            "cannot delete nested archive staging file "
                                    + temporaryArchive,
                            failure);
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
                return;
            }
            throw cleanupFailure;
        } catch (RuntimeException | Error cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
                return;
            }
            throw cleanupFailure;
        }
    }

    private static Optional<String> descriptorContract(String entryName) {
        for (String prefix : List.of(SERVICES_PATH, BOOT_CLASSES_SERVICES_PATH)) {
            if (!entryName.startsWith(prefix)) {
                continue;
            }
            String contract = entryName.substring(prefix.length());
            if (!contract.isEmpty() && !contract.contains("/")) {
                return Optional.of(contract);
            }
        }
        return Optional.empty();
    }

    private static boolean isBootNestedArchive(String entryName) {
        if (!entryName.startsWith(BOOT_LIB_PATH)) {
            return false;
        }
        String relativeName = entryName.substring(BOOT_LIB_PATH.length());
        return !relativeName.isEmpty() && !relativeName.contains("/");
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
        if (content.startsWith("\ufeff")) {
            content = content.substring(1);
        }
        if (content.indexOf('\ufeff') >= 0) {
            throw new IllegalArgumentException(
                    "embedded UTF-8 BOM in service descriptor " + source);
        }
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

    private static ServiceDescriptor parseServiceDescriptorBytes(
            String source, String contractName, byte[] content) {
        return parseServiceDescriptor(
                source, contractName, decodeStrictUtf8(content, source));
    }

    private static String decodeStrictUtf8(byte[] content, String source) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(
                    "service descriptor is not valid UTF-8: " + source,
                    failure);
        }
    }

    private static byte[] readRelevantEntry(
            InputStream input,
            EntryReadBudget readBudget,
            String source,
            LongConsumer byteRecorder) throws IOException {
        try (input; ByteArrayOutputStream content = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            long total = 0;
            while (true) {
                long remaining = readBudget.maxBytes() - total;
                int requested =
                        (int) (remaining < buffer.length ? remaining + 1 : buffer.length);
                int read = input.read(buffer, 0, requested);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                if (read > remaining) {
                    throw new IllegalStateException(
                            readBudget.limitName() + " exceeded while reading " + source);
                }
                content.write(buffer, 0, read);
                total += read;
            }
            byteRecorder.accept(total);
            return content.toByteArray();
        }
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

    private record DynamicCodeUnitKey(
            String ownerName, String methodName, String descriptor) {}

    private record DynamicTargetEvidence(
            boolean inspected,
            boolean ambiguous,
            Set<String> stringConstants,
            Set<String> typeConstants) {

        private DynamicTargetEvidence {
            stringConstants = Set.copyOf(stringConstants);
            typeConstants = Set.copyOf(typeConstants);
        }

        private static DynamicTargetEvidence uninspected() {
            return new DynamicTargetEvidence(false, true, Set.of(), Set.of());
        }

        private static DynamicTargetEvidence unresolved() {
            return new DynamicTargetEvidence(true, false, Set.of(), Set.of());
        }

        private static DynamicTargetEvidence ambiguousTarget() {
            return new DynamicTargetEvidence(true, true, Set.of(), Set.of());
        }
    }

    private record DynamicMethodEvidence(
            boolean inspected, List<DynamicInvocationEvidence> invocations) {

        private DynamicMethodEvidence {
            invocations = List.copyOf(invocations);
        }

        private static DynamicMethodEvidence uninspected() {
            return new DynamicMethodEvidence(false, List.of());
        }
    }

    private record DynamicInvocationEvidence(
            String targetOwnerName,
            String targetName,
            String targetDescriptor,
            int lineNumber,
            int invocationOrdinal,
            DynamicTargetEvidence targetEvidence) {

        private boolean matchesSignature(
                String ownerName, String name, String descriptor) {
            return targetOwnerName.equals(ownerName)
                    && targetName.equals(name)
                    && (descriptor == null || targetDescriptor.equals(descriptor));
        }

        private DynamicInvocationEvidence withTargetEvidence(
                DynamicTargetEvidence evidence) {
            return new DynamicInvocationEvidence(
                    targetOwnerName,
                    targetName,
                    targetDescriptor,
                    lineNumber,
                    invocationOrdinal,
                    evidence);
        }
    }

    private record DynamicStackValue(
            int size,
            DynamicTargetEvidence targetEvidence,
            Set<Integer> deferredInvocationIndexes) {

        private DynamicStackValue {
            if (size != 1 && size != 2) {
                throw new IllegalArgumentException("JVM stack value size must be one or two");
            }
            deferredInvocationIndexes = Set.copyOf(deferredInvocationIndexes);
        }

        private static DynamicStackValue unresolved(int size) {
            return new DynamicStackValue(
                    size, DynamicTargetEvidence.unresolved(), Set.of());
        }

        private static DynamicStackValue ambiguous(int size) {
            return new DynamicStackValue(
                    size, DynamicTargetEvidence.ambiguousTarget(), Set.of());
        }

        private static DynamicStackValue stringConstant(String value) {
            return new DynamicStackValue(
                    1,
                    new DynamicTargetEvidence(
                            true, false, Set.of(value), Set.of()),
                    Set.of());
        }

        private static DynamicStackValue typeConstant(String value) {
            return new DynamicStackValue(
                    1,
                    new DynamicTargetEvidence(
                            true, false, Set.of(), Set.of(value)),
                    Set.of());
        }
    }

    private record ServiceDescriptor(
            String source, String contractName, Set<String> providerNames) {

        private ServiceDescriptor {
            providerNames = Collections.unmodifiableSet(new TreeSet<>(providerNames));
        }
    }

    private record ServiceDescriptorCatalog(List<ServiceDescriptor> descriptors) {

        private ServiceDescriptorCatalog {
            descriptors =
                    descriptors.stream()
                            .distinct()
                            .sorted(
                                    Comparator.comparing(ServiceDescriptor::source)
                                            .thenComparing(ServiceDescriptor::contractName))
                            .toList();
        }

        private static ServiceDescriptorCatalog merge(
                ServiceDescriptorCatalog... catalogs) {
            return new ServiceDescriptorCatalog(
                    Arrays.stream(catalogs)
                            .flatMap(catalog -> catalog.descriptors().stream())
                            .toList());
        }

        private Set<String> ownedProviderNames() {
            Set<String> providers = new TreeSet<>();
            descriptors.stream()
                    .flatMap(descriptor -> descriptor.providerNames().stream())
                    .filter(provider -> provider.startsWith(OWNED_PACKAGE_PREFIX))
                    .forEach(providers::add);
            return Collections.unmodifiableSet(providers);
        }
    }

    private record ServiceProviderResolution(
            Set<String> ownedProviderNames,
            List<String> missingOwnedProviderRegistrations,
            List<String> externalProviderRegistrations) {}

    private record ClassPathScanEntry(
            Path path, int manifestHopDepth, String source) {}

    private record ClassPathTraversalKey(
            String canonicalFileIdentity, String lexicalManifestBase) {}

    private record CanonicalClassPathEntry(
            Path path, ClassPathTraversalKey traversalKey) {}

    private record ManifestScanContext(Path ownerArchive, int manifestHopDepth) {}

    private record ScanLimits(
            long maxDescriptorBytes,
            long maxManifestBytes,
            long maxNestedArchiveBytes,
            long maxArchiveFileBytes,
            long maxResolvedClassPathEntries,
            long maxClasspathArchiveCount,
            long maxManifestHopDepth,
            long maxCentralDirectoryBytes,
            long maxTotalArchiveEntries,
            long maxNestedArchiveCount,
            long maxNestedArchiveEntries,
            long maxRelevantEntries,
            long maxTotalRelevantExpandedBytes) {

        private ScanLimits {
            if (maxDescriptorBytes < 0
                    || maxManifestBytes < 0
                    || maxNestedArchiveBytes < 0
                    || maxArchiveFileBytes < 0
                    || maxResolvedClassPathEntries < 0
                    || maxClasspathArchiveCount < 0
                    || maxManifestHopDepth < 0
                    || maxCentralDirectoryBytes < 0
                    || maxTotalArchiveEntries < 0
                    || maxNestedArchiveCount < 0
                    || maxNestedArchiveEntries < 0
                    || maxRelevantEntries < 0
                    || maxTotalRelevantExpandedBytes < 0) {
                throw new IllegalArgumentException("service descriptor scan limits cannot be negative");
            }
        }
    }

    private record EntryReadBudget(long maxBytes, String limitName) {}

    private record EndOfCentralDirectory(
            long offset,
            long centralDirectoryOffset,
            long centralDirectoryBytes,
            int entryCount) {}

    private record ZipPreflight(List<String> sortedEntryNames, int nestedDepth) {

        private ZipPreflight {
            sortedEntryNames = List.copyOf(sortedEntryNames);
        }
    }

    @FunctionalInterface
    private interface TemporaryArchiveDeleter {
        boolean delete(Path path) throws IOException;
    }

    private static final class ScanBudget {

        private final ScanLimits limits;
        private long resolvedClassPathEntries;
        private long classpathArchiveCount;
        private long totalArchiveEntries;
        private long nestedArchiveCount;
        private long nestedArchiveEntries;
        private long relevantEntries;
        private long totalRelevantExpandedBytes;

        private ScanBudget(ScanLimits limits) {
            this.limits = limits;
        }

        private void recordResolvedClassPathEntry(String source) {
            resolvedClassPathEntries++;
            if (resolvedClassPathEntries > limits.maxResolvedClassPathEntries()) {
                throw new IllegalStateException(
                        "resolved classpath entry limit exceeded at " + source);
            }
        }

        private void checkManifestHopDepth(int manifestHopDepth, String source) {
            if (manifestHopDepth > limits.maxManifestHopDepth()) {
                throw new IllegalStateException(
                        "manifest Class-Path hop depth limit exceeded at " + source);
            }
        }

        private void reserveClasspathArchive(long archiveBytes, String source) {
            classpathArchiveCount++;
            if (classpathArchiveCount > limits.maxClasspathArchiveCount()) {
                throw new IllegalStateException(
                        "classpath archive count limit exceeded at " + source);
            }
            checkArchiveFileBytes(archiveBytes, source);
        }

        private void checkArchiveFileBytes(long archiveBytes, String source) {
            if (archiveBytes > limits.maxArchiveFileBytes()) {
                throw new IllegalStateException(
                        "archive file byte limit exceeded at " + source);
            }
        }

        private void recordCentralDirectoryBytes(long bytes, String source) {
            if (bytes > limits.maxCentralDirectoryBytes()) {
                throw new IllegalStateException(
                        "central directory byte limit exceeded in " + source);
            }
        }

        private void recordArchiveEntries(
                long entries, boolean nestedArchive, String source) {
            if (entries > limits.maxTotalArchiveEntries() - totalArchiveEntries) {
                throw new IllegalStateException(
                        "total archive entry limit exceeded in " + source);
            }
            totalArchiveEntries += entries;
            if (nestedArchive) {
                if (entries
                        > limits.maxNestedArchiveEntries()
                                - nestedArchiveEntries) {
                    throw new IllegalStateException(
                            "nested archive entry limit exceeded in " + source);
                }
                nestedArchiveEntries += entries;
            }
        }

        private EntryReadBudget descriptorReadBudget() {
            return readBudget(limits.maxDescriptorBytes(), "descriptor byte limit");
        }

        private EntryReadBudget manifestReadBudget() {
            return readBudget(limits.maxManifestBytes(), "manifest byte limit");
        }

        private EntryReadBudget nestedArchiveReadBudget() {
            return readBudget(limits.maxNestedArchiveBytes(), "nested archive byte limit");
        }

        private EntryReadBudget readBudget(long entryLimit, String entryLimitName) {
            long totalRemaining =
                    limits.maxTotalRelevantExpandedBytes()
                            - totalRelevantExpandedBytes;
            if (totalRemaining < entryLimit) {
                return new EntryReadBudget(
                        totalRemaining, "total relevant expanded byte limit");
            }
            return new EntryReadBudget(entryLimit, entryLimitName);
        }

        private void reserveNestedArchive(String source) {
            nestedArchiveCount++;
            if (nestedArchiveCount > limits.maxNestedArchiveCount()) {
                throw new IllegalStateException(
                        "nested archive count limit exceeded at " + source);
            }
        }

        private void recordRelevantEntry(String source) {
            relevantEntries++;
            if (relevantEntries > limits.maxRelevantEntries()) {
                throw new IllegalStateException(
                        "relevant entry limit exceeded at " + source);
            }
        }

        private void recordDescriptorBytes(long bytes) {
            recordRelevantExpandedBytes(bytes);
        }

        private void recordManifestBytes(long bytes) {
            recordRelevantExpandedBytes(bytes);
        }

        private void recordNestedArchiveBytes(long bytes) {
            recordRelevantExpandedBytes(bytes);
        }

        private void recordRelevantExpandedBytes(long bytes) {
            if (bytes > limits.maxTotalRelevantExpandedBytes()
                    - totalRelevantExpandedBytes) {
                throw new IllegalStateException(
                        "total relevant expanded byte limit exceeded");
            }
            totalRelevantExpandedBytes += bytes;
        }
    }
}
