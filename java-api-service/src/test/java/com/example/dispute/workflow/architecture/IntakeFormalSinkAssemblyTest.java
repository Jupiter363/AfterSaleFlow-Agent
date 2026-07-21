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
import java.nio.channels.FileChannel;
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
    private static final int MAX_EOCD_BYTES = 65_557;
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final long ZIP64_EOCD_LOCATOR_SIGNATURE = 0x07064b50L;
    private static final long CENTRAL_FILE_HEADER_SIGNATURE = 0x02014b50L;
    private static final long LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L;

    private static final ScanLimits PRODUCTION_SCAN_LIMITS =
            new ScanLimits(
                    1_048_576,
                    67_108_864,
                    1_073_741_824,
                    4_096,
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
        assertCleanupFailurePolicy(tempDirectory);
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
                maxNestedArchiveBytes,
                1_048_576,
                100,
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
                limits.maxNestedArchiveBytes(),
                maxArchiveFileBytes,
                maxClasspathArchiveCount,
                maxCentralDirectoryBytes,
                maxTotalArchiveEntries,
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
        try {
            String source = archivePath.toString();
            budget.reserveClasspathArchive(Files.size(archivePath), source);
            ZipPreflight preflight =
                    preflightArchive(archivePath, source, 0, budget);
            try (JarFile archive = new JarFile(archivePath.toFile())) {
                scanArchiveEntries(
                        archive,
                        source,
                        preflight,
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

    private static void scanArchiveEntries(
            JarFile archive,
            String source,
            ZipPreflight preflight,
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
                            || isBootNestedArchive(entry.getName()))) {
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
                        preflight.nestedDepth() + 1,
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
        Throwable primaryFailure = null;
        try {
            temporaryArchive = Files.createTempFile("intake-service-provider-", ".zip");
            Files.write(temporaryArchive, archiveBytes);
            ZipPreflight preflight =
                    preflightArchive(
                            temporaryArchive, source, depth, budget);
            try (JarFile archive = new JarFile(temporaryArchive.toFile())) {
                scanArchiveEntries(
                        archive, source, preflight, descriptors, budget);
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
            long maxArchiveFileBytes,
            long maxClasspathArchiveCount,
            long maxCentralDirectoryBytes,
            long maxTotalArchiveEntries,
            long maxNestedArchiveCount,
            long maxNestedArchiveEntries,
            long maxRelevantEntries,
            long maxTotalRelevantExpandedBytes) {

        private ScanLimits {
            if (maxDescriptorBytes < 0
                    || maxNestedArchiveBytes < 0
                    || maxArchiveFileBytes < 0
                    || maxClasspathArchiveCount < 0
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
        private long classpathArchiveCount;
        private long totalArchiveEntries;
        private long nestedArchiveCount;
        private long nestedArchiveEntries;
        private long relevantEntries;
        private long totalRelevantExpandedBytes;

        private ScanBudget(ScanLimits limits) {
            this.limits = limits;
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
