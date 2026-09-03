package com.example.dispute.workflow.targete2e.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphProposalSourceClient;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphReconciliationClient;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeSigner;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.rooms.hearing.HearingFormalReceiptTargetCommitPort;
import com.example.dispute.workflow.targete2e.rooms.hearing.JdbcTargetHearingPublicTranscriptCommitter;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingRegistrationBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class TargetE2eHearingArtifactConfigurationTest {

  private static final String CONFIGURATION_CLASS =
      "com.example.dispute.workflow.targete2e.artifact."
          + "TargetE2eHearingArtifactConfiguration";
  private static final Path TARGET_CLASSES = Path.of("target", "target-e2e-classes");

  @Test
  void agentAssemblyProvidesAndInjectsTheRealHearingPublicTranscriptCommitter()
      throws Exception {
    assertThat(TARGET_CLASSES.resolve(CONFIGURATION_CLASS.replace('.', '/') + ".class"))
        .isRegularFile();

    try (URLClassLoader loader =
            new URLClassLoader(
                new java.net.URL[] {TARGET_CLASSES.toUri().toURL()}, getClass().getClassLoader());
        AnnotationConfigApplicationContext context = context(loader, "AGENT")) {
      DataSource dataSource = mock(DataSource.class);
      CaseEventService caseEvents = mock(CaseEventService.class);
      registerAgentDependencies(context, dataSource, caseEvents);
      context.register(configuration(loader));
      context.refresh();

      JdbcTargetHearingPublicTranscriptCommitter transcript =
          context.getBean(
              "targetHearingPublicTranscriptCommitter",
              JdbcTargetHearingPublicTranscriptCommitter.class);
      assertThat(transcript).isExactlyInstanceOf(JdbcTargetHearingPublicTranscriptCommitter.class);

      TargetHearingRegistrationBundle bundle =
          context.getBean(TargetHearingRegistrationBundle.class);
      Object formalCommitPort =
          ReflectionTestUtils.getField(bundle.domainCommitter(), "formalCommitPort");
      assertThat(formalCommitPort).isInstanceOf(HearingFormalReceiptTargetCommitPort.class);
      assertThat(ReflectionTestUtils.getField(formalCommitPort, "transcript"))
          .isSameAs(transcript);

      @SuppressWarnings("unchecked")
      Consumer<String> notifier =
          (Consumer<String>) ReflectionTestUtils.getField(transcript, "afterCommitNotifier");
      assertThat(notifier).isNotNull();
      notifier.accept("CASE_AGENT_TRANSCRIPT_ASSEMBLY");
      verify(caseEvents).wakeUp("CASE_AGENT_TRANSCRIPT_ASSEMBLY");
    }

    try (URLClassLoader loader =
            new URLClassLoader(
                new java.net.URL[] {TARGET_CLASSES.toUri().toURL()}, getClass().getClassLoader());
        AnnotationConfigApplicationContext context = context(loader, "CONTROL")) {
      context.register(configuration(loader));
      context.refresh();
      assertThat(context.getBeansOfType(JdbcTargetHearingPublicTranscriptCommitter.class)).isEmpty();
    }
  }

  private static AnnotationConfigApplicationContext context(
      ClassLoader loader, String workerRole) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.setClassLoader(loader);
    context.getEnvironment().setActiveProfiles("target-e2e");
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "agent-assembly-test", Map.of("app.temporal.worker.role", workerRole)));
    return context;
  }

  private static Class<?> configuration(ClassLoader loader) throws ClassNotFoundException {
    return Class.forName(CONFIGURATION_CLASS, true, loader);
  }

  private static void registerAgentDependencies(
      AnnotationConfigApplicationContext context,
      DataSource dataSource,
      CaseEventService caseEvents) {
    context.registerBean(DataSource.class, () -> dataSource);
    context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
    context.registerBean(CaseEventService.class, () -> caseEvents);
    context.registerBean(
        TargetE2EActivationLedger.class, () -> mock(TargetE2EActivationLedger.class));
    context.registerBean(
        TargetE2EGraphEnvelopeCodec.class, () -> mock(TargetE2EGraphEnvelopeCodec.class));
    context.registerBean(
        TargetE2EGraphEnvelopeSigner.class, () -> mock(TargetE2EGraphEnvelopeSigner.class));
    context.registerBean(
        HttpTargetE2EGraphReconciliationClient.class,
        () -> mock(HttpTargetE2EGraphReconciliationClient.class));
    context.registerBean(
        HttpTargetE2EGraphProposalSourceClient.class,
        () -> mock(HttpTargetE2EGraphProposalSourceClient.class));
    context.registerBean(
        GraphRegistryBindingPolicy.class, () -> mock(GraphRegistryBindingPolicy.class));
    context.registerBean(
        TargetE2eFinalizationRuntimeContextProvider.class,
        () -> mock(TargetE2eFinalizationRuntimeContextProvider.class));
    context.registerBean(
        TargetE2eFinalizationActivationPort.class,
        () -> mock(TargetE2eFinalizationActivationPort.class));
    context.registerBean(MinioClient.class, () -> mock(MinioClient.class));
    context.registerBean(
        PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
  }
}
