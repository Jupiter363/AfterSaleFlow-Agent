package com.example.dispute.workflow.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.example.dispute.workflow.temporal")
class TemporalWorkflowDependencyTest {

    @ArchTest
    static final ArchRule WORKFLOW_IMPLEMENTATIONS_MUST_NOT_USE_IO_OR_SPRING =
            noClasses()
                    .that()
                    .haveSimpleNameEndingWith("WorkflowImpl")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..infrastructure..",
                            "..persistence..",
                            "..repository..",
                            "org.springframework..",
                            "java.io..",
                            "java.net..",
                            "java.nio.file..",
                            "java.sql..",
                            "javax.sql..",
                            "okhttp3..",
                            "org.apache.hc..",
                            "co.elastic.clients..",
                            "io.minio..")
                    .because(
                            "Temporal Workflow implementations must replay without repositories, "
                                    + "HTTP, filesystem, database, or framework I/O");

    @ArchTest
    static final ArchRule WORKFLOW_IMPLEMENTATIONS_MUST_NOT_USE_NONDETERMINISTIC_CLOCKS =
            noClasses()
                    .that()
                    .haveSimpleNameEndingWith("WorkflowImpl")
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.lang.System")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.time.Clock")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.util.Random")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.security.SecureRandom")
                    .because(
                            "Workflow time and randomness must come from Temporal "
                                    + "deterministic APIs");
}
