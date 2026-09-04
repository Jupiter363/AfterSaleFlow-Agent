package com.example.dispute.workflow.runtime.artifact;

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

class ProductionArtifactIsolationIT {

    private static final String TARGET_CLASS_PREFIX =
            "BOOT-INF/classes/com/example/dispute/workflow/runtime/artifact/";
    private static final String NON_DISCOVERABLE_FINALIZER_LIBRARY =
            "BOOT-INF/classes/com/example/dispute/workflow/runtime/finalization/"
                    + "ProductionExecutionLaneVerifier.class";
    private static final String NON_DISCOVERABLE_OUTPUT_MATERIALIZER_LIBRARY =
            "BOOT-INF/classes/com/example/dispute/workflow/runtime/finalization/"
                    + "ProductionGraphOutputSnapshotMaterializer.class";
    private static final String NON_DISCOVERABLE_MULTI_ROOM_FINALIZER_LIBRARY =
            "BOOT-INF/classes/com/example/dispute/workflow/runtime/finalization/"
                    + "ProductionMultiRoomOuterFinalizer.class";
    private static final String PROCESSOR_CLASS =
            TARGET_CLASS_PREFIX + "ProductionEnvironmentPostProcessor.class";
    private static final String CONFIGURATION_CLASS =
            TARGET_CLASS_PREFIX + "ProductionArtifactConfiguration.class";
    private static final String API_CONFIGURATION_CLASS =
            TARGET_CLASS_PREFIX + "ProductionApiConfiguration.class";
    private static final String CONTROL_CONFIGURATION_CLASS =
            TARGET_CLASS_PREFIX + "ProductionControlConfiguration.class";
    private static final String TARGET_INTAKE_BRANCH_ACTIVITY =
            "BOOT-INF/classes/com/example/dispute/workflow/runtime/rooms/intake/"
                    + "ProductionIntakeRoomActivities.class";
    private static final String TARGET_INTAKE_BRANCH_RESOLVER =
            "BOOT-INF/classes/com/example/dispute/workflow/runtime/rooms/intake/"
                    + "ProductionIntakeFormalBranchCommandResolver.class";
    private static final String TARGET_APPLICATION_CONFIGURATION =
            "BOOT-INF/classes/application-production-runtime.yml";
    private static final String MARKER_RESOURCE =
            "META-INF/after-sale-flow/production-runtime-artifact.marker";
    private static final String SPRING_FACTORIES = "META-INF/spring.factories";

    @Test
    void ordinaryJarExcludesTargetOnlyAssemblyAndClassifiedJarContainsIt() throws IOException {
        Path buildDirectory = Path.of(requiredProperty("production.buildDirectory"));
        String finalName = requiredProperty("production.finalName");
        String classifier = requiredProperty("production.classifier");
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
                            "ProductionEnvironmentPostProcessor");
            assertThat(readEntry(target, MARKER_RESOURCE).strip())
                    .isEqualTo("PRODUCTION_RUNTIME_JAVA_ARTIFACT_V1");
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
                "PRODUCTION_RUNTIME_PROFILE_REQUIRED",
                "--spring.profiles.active=agent-worker",
                "--app.production-runtime.activation.manifest-jws=" + activation);
        assertStartupRejected(
                targetJar,
                tempDirectory.resolve("disabled-worker.log"),
                "PRODUCTION_RUNTIME_WORKER_DISABLED",
                "--spring.profiles.active=production-runtime,agent-worker",
                "--app.temporal.worker.enabled=false",
                "--app.temporal.worker.role=AGENT");
        assertStartupRejected(
                targetJar,
                tempDirectory.resolve("wrong-worker-role.log"),
                "PRODUCTION_RUNTIME_WORKER_ROLE_INVALID",
                "--spring.profiles.active=production-runtime,agent-worker",
                "--app.temporal.worker.role=UNKNOWN",
                "--app.production-runtime.activation.manifest-jws=" + activation);
        assertStartupRejected(
                targetJar,
                tempDirectory.resolve("missing-activation.log"),
                "PRODUCTION_RUNTIME_ACTIVATION_REQUIRED",
                "--spring.profiles.active=production-runtime,control-worker",
                "--app.temporal.worker.enabled=true",
                "--app.temporal.worker.role=CONTROL",
                "--app.temporal.worker.versioning-mode=BUILD_ID",
                "--app.agent-run-v2.protocol-default=V3",
                "--app.agent-run-v2.scheduler-mode=DETECTOR",
                "--app.production-runtime.activation.manifest-jws=");
        assertStartupRejected(
                targetJar,
                tempDirectory.resolve("missing-versioning.log"),
                "PRODUCTION_RUNTIME_WORKER_VERSIONING_REQUIRED",
                "--spring.profiles.active=production-runtime,agent-worker",
                "--app.temporal.worker.enabled=true",
                "--app.temporal.worker.role=AGENT");
        assertStartupRejected(
                targetJar,
                tempDirectory.resolve("disabled-agent-run-v2.log"),
                "PRODUCTION_RUNTIME_AGENT_RUN_V2_REQUIRED",
                "--spring.profiles.active=production-runtime,agent-worker",
                "--app.temporal.worker.enabled=true",
                "--app.temporal.worker.role=AGENT",
                "--app.temporal.worker.versioning-mode=BUILD_ID",
                "--app.agent-run-v2.protocol-default=V3",
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
        assertThat(exited).as("production runtime artifact must reject incomplete startup promptly").isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(Files.readString(output, StandardCharsets.UTF_8)).contains(expectedFailureCode);
    }

    private static Path targetJar() {
        Path buildDirectory = Path.of(requiredProperty("production.buildDirectory"));
        return buildDirectory.resolve(
                requiredProperty("production.finalName")
                        + "-"
                        + requiredProperty("production.classifier")
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
