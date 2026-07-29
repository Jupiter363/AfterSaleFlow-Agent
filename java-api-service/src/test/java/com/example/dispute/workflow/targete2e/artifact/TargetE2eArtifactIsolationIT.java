package com.example.dispute.workflow.targete2e.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetE2eArtifactIsolationIT {

    private static final String TARGET_CLASS_PREFIX =
            "BOOT-INF/classes/com/example/dispute/workflow/targete2e/artifact/";
    private static final String NON_DISCOVERABLE_FINALIZER_LIBRARY =
            "BOOT-INF/classes/com/example/dispute/workflow/targete2e/finalization/"
                    + "TargetE2eExecutionLaneVerifier.class";
    private static final String NON_DISCOVERABLE_OUTPUT_MATERIALIZER_LIBRARY =
            "BOOT-INF/classes/com/example/dispute/workflow/targete2e/finalization/"
                    + "TargetE2eGraphOutputSnapshotMaterializer.class";
    private static final String NON_DISCOVERABLE_MULTI_ROOM_FINALIZER_LIBRARY =
            "BOOT-INF/classes/com/example/dispute/workflow/targete2e/finalization/"
                    + "TargetE2eMultiRoomOuterFinalizer.class";
    private static final String PROCESSOR_CLASS =
            TARGET_CLASS_PREFIX + "TargetE2eEnvironmentPostProcessor.class";
    private static final String CONFIGURATION_CLASS =
            TARGET_CLASS_PREFIX + "TargetE2eArtifactConfiguration.class";
    private static final String API_CONFIGURATION_CLASS =
            TARGET_CLASS_PREFIX + "TargetE2eApiConfiguration.class";
    private static final String CONTROL_CONFIGURATION_CLASS =
            TARGET_CLASS_PREFIX + "TargetE2eControlConfiguration.class";
    private static final String TARGET_INTAKE_BRANCH_ACTIVITY =
            "BOOT-INF/classes/com/example/dispute/workflow/targete2e/rooms/intake/"
                    + "TargetE2eIntakeRoomActivities.class";
    private static final String TARGET_INTAKE_BRANCH_RESOLVER =
            "BOOT-INF/classes/com/example/dispute/workflow/targete2e/rooms/intake/"
                    + "TargetE2eIntakeFormalBranchCommandResolver.class";
    private static final String TARGET_APPLICATION_CONFIGURATION =
            "BOOT-INF/classes/application-target-e2e.yml";
    private static final String MARKER_RESOURCE =
            "META-INF/after-sale-flow/target-e2e-artifact.marker";
    private static final String SPRING_FACTORIES = "META-INF/spring.factories";

    @Test
    void ordinaryJarExcludesTargetOnlyAssemblyAndClassifiedJarContainsIt() throws IOException {
        Path buildDirectory = Path.of(requiredProperty("targetE2e.buildDirectory"));
        String finalName = requiredProperty("targetE2e.finalName");
        String classifier = requiredProperty("targetE2e.classifier");
        Path ordinaryJar = buildDirectory.resolve(finalName + ".jar");
        Path targetJar = buildDirectory.resolve(finalName + "-" + classifier + ".jar");

        try (JarFile ordinary = new JarFile(ordinaryJar.toFile());
                JarFile target = new JarFile(targetJar.toFile())) {
            assertThat(ordinary.stream().map(entry -> entry.getName()))
                    .noneMatch(name -> name.startsWith(TARGET_CLASS_PREFIX))
                    .doesNotContain(MARKER_RESOURCE)
                    .contains(
                            NON_DISCOVERABLE_FINALIZER_LIBRARY,
                            NON_DISCOVERABLE_OUTPUT_MATERIALIZER_LIBRARY,
                            NON_DISCOVERABLE_MULTI_ROOM_FINALIZER_LIBRARY);

            assertThat(target.stream().map(entry -> entry.getName()))
                    .contains(
                            PROCESSOR_CLASS,
                            CONFIGURATION_CLASS,
                            API_CONFIGURATION_CLASS,
                            CONTROL_CONFIGURATION_CLASS,
                            TARGET_INTAKE_BRANCH_ACTIVITY,
                            TARGET_INTAKE_BRANCH_RESOLVER,
                            MARKER_RESOURCE,
                            TARGET_APPLICATION_CONFIGURATION,
                            SPRING_FACTORIES,
                            NON_DISCOVERABLE_FINALIZER_LIBRARY,
                            NON_DISCOVERABLE_OUTPUT_MATERIALIZER_LIBRARY,
                            NON_DISCOVERABLE_MULTI_ROOM_FINALIZER_LIBRARY)
                    .contains("BOOT-INF/classes/com/example/dispute/DisputeApplication.class")
                    .contains("org/springframework/boot/loader/launch/JarLauncher.class");

            String factories = readEntry(target, SPRING_FACTORIES);
            assertThat(factories)
                    .contains(
                            "org.springframework.boot.env.EnvironmentPostProcessor=",
                            "TargetE2eEnvironmentPostProcessor");
            assertThat(readEntry(target, MARKER_RESOURCE).strip())
                    .isEqualTo("TARGET_E2E_JAVA_ARTIFACT_V1");
        }
    }

    @Test
    void classifiedJarFailsBeforeContextCreationWhenAnyRuntimePrerequisiteIsMissing(
            @TempDir Path tempDirectory) throws Exception {
        Path targetJar = targetJar();
        String activation = structurallyValidActivationJws();

        assertStartupRejected(
                targetJar,
                tempDirectory.resolve("missing-profile.log"),
                "TARGET_E2E_PROFILE_REQUIRED",
                "--spring.profiles.active=agent-worker",
                "--app.target-e2e.activation.manifest-jws=" + activation);
        assertStartupRejected(
                targetJar,
                tempDirectory.resolve("disabled-worker.log"),
                "TARGET_E2E_WORKER_DISABLED",
                "--spring.profiles.active=target-e2e,agent-worker",
                "--app.temporal.worker.enabled=false",
                "--app.temporal.worker.role=AGENT");
        assertStartupRejected(
                targetJar,
                tempDirectory.resolve("wrong-worker-role.log"),
                "TARGET_E2E_WORKER_ROLE_INVALID",
                "--spring.profiles.active=target-e2e,agent-worker",
                "--app.temporal.worker.role=UNKNOWN",
                "--app.target-e2e.activation.manifest-jws=" + activation);
        assertStartupRejected(
                targetJar,
                tempDirectory.resolve("missing-activation.log"),
                "TARGET_E2E_ACTIVATION_REQUIRED",
                "--spring.profiles.active=target-e2e,control-worker",
                "--app.temporal.worker.enabled=true",
                "--app.temporal.worker.role=CONTROL",
                "--app.temporal.worker.versioning-mode=BUILD_ID",
                "--app.agent-run-v2.protocol-default=V2",
                "--app.agent-run-v2.scheduler-mode=DETECTOR",
                "--app.target-e2e.activation.manifest-jws=");
        assertStartupRejected(
                targetJar,
                tempDirectory.resolve("missing-versioning.log"),
                "TARGET_E2E_WORKER_VERSIONING_REQUIRED",
                "--spring.profiles.active=target-e2e,agent-worker",
                "--app.temporal.worker.enabled=true",
                "--app.temporal.worker.role=AGENT");
        assertStartupRejected(
                targetJar,
                tempDirectory.resolve("disabled-agent-run-v2.log"),
                "TARGET_E2E_AGENT_RUN_V2_REQUIRED",
                "--spring.profiles.active=target-e2e,agent-worker",
                "--app.temporal.worker.enabled=true",
                "--app.temporal.worker.role=AGENT",
                "--app.temporal.worker.versioning-mode=BUILD_ID",
                "--app.agent-run-v2.protocol-default=V2",
                "--app.agent-run-v2.scheduler-mode=DETECTOR");
    }

    private static void assertStartupRejected(
            Path targetJar,
            Path output,
            String expectedFailureCode,
            String... applicationArguments)
            throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", javaExecutable()).toString();
        List<String> command = new java.util.ArrayList<>();
        command.add(java);
        command.add("-jar");
        command.add(targetJar.toString());
        command.add("--spring.main.web-application-type=none");
        command.addAll(List.of(applicationArguments));

        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        boolean exited = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        assertThat(exited).as("target artifact must reject incomplete startup promptly").isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(Files.readString(output, StandardCharsets.UTF_8)).contains(expectedFailureCode);
    }

    private static Path targetJar() {
        Path buildDirectory = Path.of(requiredProperty("targetE2e.buildDirectory"));
        return buildDirectory.resolve(
                requiredProperty("targetE2e.finalName")
                        + "-"
                        + requiredProperty("targetE2e.classifier")
                        + ".jar");
    }

    private static String structurallyValidActivationJws() {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8))
                + "."
                + encoder.encodeToString("{}".getBytes(StandardCharsets.UTF_8))
                + "."
                + encoder.encodeToString(new byte[64]);
    }

    private static String javaExecutable() {
        return System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    }

    private static String readEntry(JarFile jar, String name) throws IOException {
        return new String(
                jar.getInputStream(jar.getJarEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertThat(value).as("required system property %s", name).isNotBlank();
        return value;
    }
}
