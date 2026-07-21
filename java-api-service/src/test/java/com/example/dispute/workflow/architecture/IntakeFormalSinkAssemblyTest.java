package com.example.dispute.workflow.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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

    private static final Set<String> FORMAL_ROOT_SIMPLE_NAMES =
            Set.of(
                    "IntakeFormalCommitPort",
                    "IntakeFormalBranchCommitPort",
                    "IntakeTurnFinalizationPort",
                    "IntakeGraphResultFinalizer",
                    "IntakeAgentRunDomainResultCommitter");

    private static final Set<String> JAKARTA_JSR_DISCOVERY_ANNOTATIONS =
            Set.of(
                    "jakarta.annotation.ManagedBean",
                    "jakarta.inject.Named",
                    "jakarta.inject.Singleton",
                    "javax.annotation.ManagedBean",
                    "javax.inject.Named",
                    "javax.inject.Singleton");

    @ArchTest
    static final ArchRule ASSEMBLY_ROOTS_MUST_NOT_REACH_A_FORMAL_INTAKE_SINK =
            noFormalSinkAssemblyRule();

    @Test
    void compiledFixturesProveBytecodeCoverageAndSafeComparisonAssembly() {
        JavaClasses fixtures =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.OnlyIncludeTests())
                        .importPackages(FIXTURE_PACKAGE);

        String violations =
                String.join(
                        "\n",
                        ASSEMBLY_ROOTS_MUST_NOT_REACH_A_FORMAL_INTAKE_SINK
                                .evaluate(fixtures)
                                .getFailureReport()
                                .getDetails());

        assertThat(violations)
                .contains("StaticImportedFactoryBeanAssembly")
                .contains("StaticFieldAliasRegistrar")
                .contains("StaticWildcardNestedFactoryAssembly")
                .contains("QualifiedCallAndMethodReferenceAssembly")
                .contains("CrossFileWrapperAssembly")
                .contains("FixtureFormalFactory")
                .contains("CrossFileFormalDelegate")
                .contains("IntakeFormalCommitPort")
                .doesNotContain("SafeComparisonAssembly")
                .doesNotContain("LocalShadowingSafeRegistrar")
                .doesNotContain("SafeComparisonActivities");

        assertShortestChain(
                fixtures,
                "StaticImportedFactoryBeanAssembly",
                "StaticImportedFactoryBeanAssembly",
                "FixtureFormalFactory",
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
                "CrossFileFormalDelegate",
                "IntakeFormalCommitPort");

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

    private static ArchRule noFormalSinkAssemblyRule() {
        return ArchRuleDefinition.classes()
                .that(
                        new DescribedPredicate<>("are discoverable assembly or Temporal activity registration roots") {
                            @Override
                            public boolean test(JavaClass javaClass) {
                                return isAssemblyRoot(javaClass);
                            }
                        })
                .should(
                        new ArchCondition<>("not reach a formal Intake sink") {
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
                            }
                        })
                .because(
                        "Phase 4 permits only DISABLED or signed synthetic SHADOW assembly and "
                                + "forbids a discoverable formal Intake Finalizer sink");
    }

    private static boolean isAssemblyRoot(JavaClass javaClass) {
        return hasDiscoverableClassAnnotation(javaClass)
                || declaresBeanMethod(javaClass)
                || callsTemporalActivityRegistration(javaClass);
    }

    private static boolean hasDiscoverableClassAnnotation(JavaClass javaClass) {
        if (javaClass.isAnnotatedWith(SPRING_COMPONENT)
                || javaClass.isMetaAnnotatedWith(SPRING_COMPONENT)
                || javaClass.isAnnotatedWith(SPRING_CONFIGURATION)
                || javaClass.isMetaAnnotatedWith(SPRING_CONFIGURATION)) {
            return true;
        }
        return javaClass.getAnnotations().stream()
                .map(JavaAnnotation::getRawType)
                .map(JavaClass::getName)
                .anyMatch(IntakeFormalSinkAssemblyTest::isJakartaOrJsrDiscoveryAnnotation);
    }

    private static boolean isJakartaOrJsrDiscoveryAnnotation(String annotationName) {
        return JAKARTA_JSR_DISCOVERY_ANNOTATIONS.contains(annotationName)
                || annotationName.startsWith("jakarta.enterprise.context.")
                || annotationName.startsWith("javax.enterprise.context.");
    }

    private static boolean declaresBeanMethod(JavaClass javaClass) {
        return javaClass.getMethods().stream()
                .anyMatch(method -> method.isAnnotatedWith(SPRING_BEAN));
    }

    private static boolean callsTemporalActivityRegistration(JavaClass javaClass) {
        return javaClass.getMethodCallsFromSelf().stream()
                .anyMatch(
                        call ->
                                call.getTargetOwner().getName().equals(TEMPORAL_WORKER)
                                        && call.getName().equals(REGISTER_ACTIVITIES));
    }

    private static Optional<List<JavaClass>> shortestFormalSinkChain(JavaClass root) {
        ArrayDeque<PathNode> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(new PathNode(root, List.of(root)));
        visited.add(root.getName());

        while (!pending.isEmpty()) {
            PathNode current = pending.removeFirst();
            if (isFormalRoot(current.javaClass())) {
                return Optional.of(current.path());
            }

            directOwnedDependencies(current.javaClass()).stream()
                    .filter(dependency -> visited.add(dependency.getName()))
                    .forEach(
                            dependency -> {
                                List<JavaClass> path = new ArrayList<>(current.path());
                                path.add(dependency);
                                pending.addLast(new PathNode(dependency, List.copyOf(path)));
                            });
        }
        return Optional.empty();
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
}
