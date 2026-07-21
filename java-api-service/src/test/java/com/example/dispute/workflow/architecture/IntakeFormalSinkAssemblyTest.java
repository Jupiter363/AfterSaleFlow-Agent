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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;
import javax.lang.model.SourceVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    private static final String BOOT_CLASSES_SERVICES_PATH =
            "BOOT-INF/classes/META-INF/services/";
    private static final String BOOT_LIB_PATH = "BOOT-INF/lib/";
    private static final int MAX_NESTED_ARCHIVE_DEPTH = 8;

    private static final ScanLimits PRODUCTION_SCAN_LIMITS =
            new ScanLimits(
                    1_048_576,
                    67_108_864,
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
    void compiledFixturesProveBytecodeCoverageAndSafeComparisonAssembly(
            @TempDir Path tempDirectory) throws IOException {
        JavaClasses fixtures =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.OnlyIncludeTests())
                        .importPackages(FIXTURE_PACKAGE);

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
        ServiceDescriptorCatalog allFixtureDescriptors =
                ServiceDescriptorCatalog.merge(injectedDescriptors, scannedDescriptors);
        ServiceProviderResolution injectedResolution =
                resolveServiceProviders(allFixtureDescriptors, fixtures);
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
                .contains(
                        "formal Intake sink is reachable: "
                                + FIXTURE_PACKAGE
                                + ".OpaqueProvider$NestedOpaqueProvider -> "
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
        ServiceDescriptorCatalog catalog = scanClassPathEntries(fixtureClassPath);
        assertThat(catalog.descriptors())
                .filteredOn(descriptor -> descriptor.source().contains(tempDirectory.toString()))
                .hasSize(6);
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

        ScanLimits descriptorBoundary = new ScanLimits(32, 4_096, 1, 4, 4, 8_192);
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
                new ScanLimits(
                        nestedDescriptor.length,
                        nestedArchive.length,
                        1,
                        1,
                        2,
                        exactExpandedBytes);
        scanClassPathEntries(List.of(outerArchive.toString()), exactNestedBoundary);

        assertBudgetExceeded(
                outerArchive,
                new ScanLimits(
                        nestedDescriptor.length,
                        nestedArchive.length - 1L,
                        1,
                        1,
                        2,
                        exactExpandedBytes),
                "nested archive byte limit");
        assertBudgetExceeded(
                outerArchive,
                new ScanLimits(
                        nestedDescriptor.length,
                        nestedArchive.length,
                        0,
                        1,
                        2,
                        exactExpandedBytes),
                "nested archive count limit");
        assertBudgetExceeded(
                outerArchive,
                new ScanLimits(
                        nestedDescriptor.length,
                        nestedArchive.length,
                        1,
                        0,
                        2,
                        exactExpandedBytes),
                "nested archive entry limit");
        assertBudgetExceeded(
                outerArchive,
                new ScanLimits(
                        nestedDescriptor.length,
                        nestedArchive.length,
                        1,
                        1,
                        1,
                        exactExpandedBytes),
                "relevant entry limit");
        assertBudgetExceeded(
                outerArchive,
                new ScanLimits(
                        nestedDescriptor.length,
                        nestedArchive.length,
                        1,
                        1,
                        2,
                        exactExpandedBytes - 1L),
                "total relevant expanded byte limit");
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
        Set<Path> classPathEntries =
                rawEntries.stream()
                        .map(IntakeFormalSinkAssemblyTest::resolveClassPathEntry)
                        .collect(
                                Collectors.toCollection(
                                        () -> new TreeSet<>(Comparator.comparing(Path::toString))));
        List<ServiceDescriptor> descriptors = new ArrayList<>();
        ScanBudget budget = new ScanBudget(limits);
        classPathEntries.forEach(
                path -> scanClassPathEntry(path, descriptors, budget));
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

    private static void scanClassPathEntry(
            Path classPathEntry,
            List<ServiceDescriptor> descriptors,
            ScanBudget budget) {
        if (!Files.exists(classPathEntry)) {
            throw new IllegalStateException(
                    "classpath entry does not exist: " + classPathEntry);
        }
        if (!Files.isReadable(classPathEntry)) {
            throw new IllegalStateException(
                    "classpath entry is not readable: " + classPathEntry);
        }
        if (Files.isDirectory(classPathEntry)) {
            scanServiceDescriptorDirectory(classPathEntry, descriptors, budget);
            return;
        }
        if (!Files.isRegularFile(classPathEntry)) {
            throw new IllegalStateException(
                    "classpath entry is neither a directory nor a regular archive: "
                            + classPathEntry);
        }
        scanServiceDescriptorArchive(classPathEntry, descriptors, budget);
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

    private static void scanServiceDescriptorArchive(
            Path archivePath,
            List<ServiceDescriptor> descriptors,
            ScanBudget budget) {
        try (JarFile archive = new JarFile(archivePath.toFile())) {
            scanArchiveEntries(
                    archive,
                    archivePath.toString(),
                    0,
                    descriptors,
                    budget);
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

    private static void scanArchiveEntries(
            JarFile archive,
            String source,
            int nestedDepth,
            List<ServiceDescriptor> descriptors,
            ScanBudget budget) throws IOException {
        List<JarEntry> relevantEntries = new ArrayList<>();
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (nestedDepth > 0) {
                budget.recordNestedArchiveEntry(source);
            }
            if (!entry.isDirectory()
                    && (descriptorContract(entry.getName()).isPresent()
                            || isBootNestedArchive(entry.getName()))) {
                budget.recordRelevantEntry(source + "!/" + entry.getName());
                relevantEntries.add(entry);
            }
        }
        relevantEntries.sort(Comparator.comparing(JarEntry::getName));

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
            } else {
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
                        nestedDepth + 1,
                        descriptors,
                        budget);
            }
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
        try {
            temporaryArchive = Files.createTempFile("intake-service-provider-", ".zip");
            Files.write(temporaryArchive, archiveBytes);
            try (JarFile archive = new JarFile(temporaryArchive.toFile())) {
                scanArchiveEntries(
                        archive, source, depth, descriptors, budget);
            }
        } catch (ZipException failure) {
            throw new IllegalStateException(
                    "BOOT-INF/lib entry has no valid ZIP central directory: " + source,
                    failure);
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "cannot scan nested BOOT-INF/lib archive " + source,
                    failure);
        } finally {
            if (temporaryArchive != null) {
                try {
                    Files.deleteIfExists(temporaryArchive);
                } catch (IOException failure) {
                    throw new UncheckedIOException(
                            "cannot delete nested archive staging file "
                                    + temporaryArchive,
                            failure);
                }
            }
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

    private record ScanLimits(
            long maxDescriptorBytes,
            long maxNestedArchiveBytes,
            long maxNestedArchiveCount,
            long maxNestedArchiveEntries,
            long maxRelevantEntries,
            long maxTotalRelevantExpandedBytes) {

        private ScanLimits {
            if (maxDescriptorBytes < 0
                    || maxNestedArchiveBytes < 0
                    || maxNestedArchiveCount < 0
                    || maxNestedArchiveEntries < 0
                    || maxRelevantEntries < 0
                    || maxTotalRelevantExpandedBytes < 0) {
                throw new IllegalArgumentException("service descriptor scan limits cannot be negative");
            }
        }
    }

    private record EntryReadBudget(long maxBytes, String limitName) {}

    private static final class ScanBudget {

        private final ScanLimits limits;
        private long nestedArchiveCount;
        private long nestedArchiveEntries;
        private long relevantEntries;
        private long totalRelevantExpandedBytes;

        private ScanBudget(ScanLimits limits) {
            this.limits = limits;
        }

        private EntryReadBudget descriptorReadBudget() {
            return readBudget(limits.maxDescriptorBytes(), "descriptor byte limit");
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

        private void recordNestedArchiveEntry(String source) {
            nestedArchiveEntries++;
            if (nestedArchiveEntries > limits.maxNestedArchiveEntries()) {
                throw new IllegalStateException(
                        "nested archive entry limit exceeded in " + source);
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
