package com.example.dispute.workflow.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(
        packages = "com.example.dispute.workflow",
        importOptions = ImportOption.DoNotIncludeTests.class)
class TemporalWorkflowDependencyTest {

    private static final String WORKFLOW_OWNED_PACKAGE = "com.example.dispute.workflow";
    private static final String MALICIOUS_FIXTURE_PACKAGE =
            "com.example.dispute.workflow.temporal.architecturefixture";

    private static final Set<String> NONDETERMINISTIC_RANDOM_TYPES =
            Set.of(
                    "java.security.SecureRandom",
                    "java.util.concurrent.ThreadLocalRandom");

    private static final Set<String> NONDETERMINISTIC_RANDOM_CONSTRUCTORS =
            Set.of(
                    "java.util.Random",
                    "java.security.SecureRandom",
                    "java.util.SplittableRandom");

    private static final Set<String> SYSTEM_TIME_TYPES =
            Set.of(
                    "java.time.Instant",
                    "java.time.LocalDate",
                    "java.time.LocalTime",
                    "java.time.LocalDateTime",
                    "java.time.OffsetDateTime",
                    "java.time.OffsetTime",
                    "java.time.MonthDay",
                    "java.time.Year",
                    "java.time.YearMonth",
                    "java.time.ZonedDateTime");

    @ArchTest
    static final ArchRule WORKFLOW_IMPLEMENTATIONS_MUST_NOT_USE_IO_OR_FRAMEWORKS =
            workflowImplementationsShould(
                    new ReachableWorkflowClassCondition(
                            "avoid I/O, Spring, repositories, and clients",
                            TemporalWorkflowDependencyTest::checkForbiddenDependencies))
                    .because(
                            "Temporal Workflows and their helpers must replay without direct "
                                    + "repository, client, filesystem, network, database, or "
                                    + "framework I/O");

    @ArchTest
    static final ArchRule WORKFLOW_IMPLEMENTATIONS_MUST_NOT_USE_NONDETERMINISTIC_APIS =
            workflowImplementationsShould(
                    new ReachableWorkflowClassCondition(
                            "use only Temporal deterministic time and randomness",
                            TemporalWorkflowDependencyTest::checkNondeterministicApis))
                    .because(
                            "Workflow time and randomness must come from Temporal deterministic "
                                    + "APIs such as Workflow.currentTimeMillis() and "
                                    + "Workflow.randomUUID()");

    @Test
    void maliciousHelperFixtureProvesRecursiveRulesCatchEveryForbiddenApiFamily() {
        JavaClasses fixtureClasses =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.OnlyIncludeTests())
                        .importPackages(MALICIOUS_FIXTURE_PACKAGE);

        String dependencyViolations =
                String.join(
                        "\n",
                        WORKFLOW_IMPLEMENTATIONS_MUST_NOT_USE_IO_OR_FRAMEWORKS
                                .evaluate(fixtureClasses)
                                .getFailureReport()
                                .getDetails());
        assertThat(dependencyViolations)
                .contains("MaliciousWorkflowHelper")
                .contains("java.nio.file")
                .contains("org.springframework")
                .contains("MaliciousRepository")
                .contains("MaliciousClient");

        String nondeterministicViolations =
                String.join(
                        "\n",
                        WORKFLOW_IMPLEMENTATIONS_MUST_NOT_USE_NONDETERMINISTIC_APIS
                                .evaluate(fixtureClasses)
                                .getFailureReport()
                                .getDetails());
        assertThat(nondeterministicViolations)
                .contains("java.time.Instant.now")
                .contains("java.time.LocalDateTime.now")
                .contains("java.time.OffsetDateTime.now")
                .contains("java.time.ZonedDateTime.now")
                .contains("java.time.Clock.systemUTC")
                .contains("java.lang.System.currentTimeMillis")
                .contains("java.lang.System.nanoTime")
                .contains("java.util.UUID.randomUUID")
                .contains("java.util.concurrent.ThreadLocalRandom.current");
    }

    private static ArchRule workflowImplementationsShould(
            ArchCondition<JavaClass> condition) {
        return classes().that().haveSimpleNameEndingWith("WorkflowImpl").should(condition);
    }

    private static void checkForbiddenDependencies(
            JavaClass workflowRoot, JavaClass reachableClass, ConditionEvents events) {
        for (Dependency dependency : reachableClass.getDirectDependenciesFromSelf()) {
            JavaClass target = dependency.getTargetClass();
            if (isForbiddenDependency(target)) {
                events.add(
                        SimpleConditionEvent.violated(
                                workflowRoot,
                                reachableViolation(
                                        workflowRoot,
                                        reachableClass,
                                        "has forbidden dependency on " + target.getName(),
                                        dependency.getSourceCodeLocation().toString())));
            }
        }
    }

    private static boolean isForbiddenDependency(JavaClass target) {
        String packageName = target.getPackageName();
        String simpleName = target.getSimpleName();
        return containsPackageSegment(packageName, "infrastructure")
                || containsPackageSegment(packageName, "persistence")
                || containsPackageSegment(packageName, "repository")
                || packageName.startsWith("org.springframework")
                || packageName.startsWith("java.io")
                || packageName.startsWith("java.net")
                || packageName.startsWith("java.nio.channels")
                || packageName.startsWith("java.nio.file")
                || packageName.startsWith("java.sql")
                || packageName.startsWith("javax.sql")
                || packageName.startsWith("javax.persistence")
                || packageName.startsWith("jakarta.persistence")
                || packageName.startsWith("org.hibernate")
                || packageName.startsWith("org.jooq")
                || packageName.startsWith("okhttp3")
                || packageName.startsWith("org.apache.hc")
                || packageName.startsWith("co.elastic.clients")
                || packageName.startsWith("io.minio")
                || packageName.startsWith("feign")
                || packageName.startsWith("retrofit2")
                || packageName.startsWith("software.amazon.awssdk")
                || packageName.startsWith("redis.clients")
                || packageName.startsWith("io.lettuce")
                || packageName.startsWith("org.apache.kafka")
                || simpleName.endsWith("Repository")
                || simpleName.endsWith("Client");
    }

    private static boolean containsPackageSegment(String packageName, String segment) {
        return packageName.equals(segment)
                || packageName.startsWith(segment + ".")
                || packageName.endsWith("." + segment)
                || packageName.contains("." + segment + ".");
    }

    private static void checkNondeterministicApis(
            JavaClass workflowRoot, JavaClass reachableClass, ConditionEvents events) {
        for (Dependency dependency : reachableClass.getDirectDependenciesFromSelf()) {
            if (NONDETERMINISTIC_RANDOM_TYPES.contains(dependency.getTargetClass().getName())) {
                events.add(
                        SimpleConditionEvent.violated(
                                workflowRoot,
                                reachableViolation(
                                        workflowRoot,
                                        reachableClass,
                                        "depends on nondeterministic random type "
                                                + dependency.getTargetClass().getName(),
                                        dependency.getSourceCodeLocation().toString())));
            }
        }
        reachableClass.getMethodCallsFromSelf().stream()
                .filter(TemporalWorkflowDependencyTest::isNondeterministicMethod)
                .forEach(
                        access ->
                                events.add(
                                        SimpleConditionEvent.violated(
                                                workflowRoot,
                                                nondeterministicCallViolation(
                                                        workflowRoot, reachableClass, access))));
        reachableClass.getMethodReferencesFromSelf().stream()
                .filter(TemporalWorkflowDependencyTest::isNondeterministicMethod)
                .forEach(
                        access ->
                                events.add(
                                        SimpleConditionEvent.violated(
                                                workflowRoot,
                                                nondeterministicCallViolation(
                                                        workflowRoot, reachableClass, access))));
        reachableClass.getConstructorCallsFromSelf().stream()
                .filter(
                        access ->
                                NONDETERMINISTIC_RANDOM_CONSTRUCTORS.contains(
                                        access.getTargetOwner().getName()))
                .forEach(
                        access ->
                                events.add(
                                        SimpleConditionEvent.violated(
                                                workflowRoot,
                                                reachableViolation(
                                                        workflowRoot,
                                                        reachableClass,
                                                        "constructs forbidden nondeterministic "
                                                                + "random type "
                                                                + access
                                                                        .getTargetOwner()
                                                                        .getName(),
                                                        access.getSourceCodeLocation()
                                                                .toString()))));
    }

    private static boolean isNondeterministicMethod(JavaCodeUnitAccess<?> access) {
        String owner = access.getTargetOwner().getName();
        String method = access.getName();
        return (SYSTEM_TIME_TYPES.contains(owner) && method.equals("now"))
                || (owner.equals("java.time.Clock") && method.startsWith("system"))
                || (owner.equals("java.lang.System")
                        && Set.of("currentTimeMillis", "nanoTime").contains(method))
                || (owner.equals("java.util.UUID") && method.equals("randomUUID"))
                || owner.equals("java.util.concurrent.ThreadLocalRandom")
                || (owner.equals("java.lang.Math") && method.equals("random"))
                || (owner.equals("java.util.Calendar") && method.equals("getInstance"));
    }

    private static String nondeterministicCallViolation(
            JavaClass workflowRoot,
            JavaClass reachableClass,
            JavaCodeUnitAccess<?> access) {
        return reachableViolation(
                workflowRoot,
                reachableClass,
                "calls forbidden nondeterministic method "
                        + access.getTargetOwner().getName()
                        + "."
                        + access.getName(),
                access.getSourceCodeLocation().toString());
    }

    private static String reachableViolation(
            JavaClass workflowRoot,
            JavaClass reachableClass,
            String violation,
            String sourceLocation) {
        return workflowRoot.getName()
                + " reaches "
                + reachableClass.getName()
                + " which "
                + violation
                + " in "
                + sourceLocation;
    }

    @FunctionalInterface
    private interface ReachableClassCheck {
        void check(JavaClass workflowRoot, JavaClass reachableClass, ConditionEvents events);
    }

    private static final class ReachableWorkflowClassCondition
            extends ArchCondition<JavaClass> {

        private final ReachableClassCheck reachableClassCheck;

        private ReachableWorkflowClassCondition(
                String description, ReachableClassCheck reachableClassCheck) {
            super(description);
            this.reachableClassCheck = reachableClassCheck;
        }

        @Override
        public void check(JavaClass workflowRoot, ConditionEvents events) {
            for (JavaClass reachableClass : reachableWorkflowClasses(workflowRoot)) {
                reachableClassCheck.check(workflowRoot, reachableClass, events);
            }
        }

        private static List<JavaClass> reachableWorkflowClasses(JavaClass workflowRoot) {
            ArrayDeque<JavaClass> pending = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            List<JavaClass> reachable = new ArrayList<>();
            pending.add(workflowRoot);

            while (!pending.isEmpty()) {
                JavaClass current = pending.removeFirst();
                if (!visited.add(current.getName())) {
                    continue;
                }
                reachable.add(current);
                current.getDirectDependenciesFromSelf().stream()
                        .map(Dependency::getTargetClass)
                        .filter(isWorkflowOwned())
                        .forEach(pending::addLast);
            }
            return reachable;
        }

        private static Predicate<JavaClass> isWorkflowOwned() {
            return javaClass ->
                    javaClass.getPackageName().equals(WORKFLOW_OWNED_PACKAGE)
                            || javaClass
                                    .getPackageName()
                                    .startsWith(WORKFLOW_OWNED_PACKAGE + ".");
        }
    }
}
