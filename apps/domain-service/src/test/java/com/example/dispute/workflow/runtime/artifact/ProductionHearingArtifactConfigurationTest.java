package com.example.dispute.workflow.runtime.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.runtime.graph.HttpProductionGraphProposalSourceClient;
import com.example.dispute.workflow.runtime.graph.HttpProductionGraphReconciliationClient;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeSigner;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.rooms.hearing.HearingFormalReceiptTargetCommitPort;
import com.example.dispute.workflow.runtime.rooms.hearing.JdbcTargetHearingPublicTranscriptCommitter;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingRegistrationBundle;
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

class ProductionHearingArtifactConfigurationTest {

  private static final String CONFIGURATION_CLASS =
      "com.example.dispute.workflow.runtime.artifact."
          + "ProductionHearingArtifactConfiguration";
  private static final Path TARGET_CLASSES = Path.of("target", "production-runtime-classes");

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
    context.getEnvironment().setActiveProfiles("production-runtime");
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
        ProductionActivationLedger.class, () -> mock(ProductionActivationLedger.class));
    context.registerBean(
        ProductionGraphEnvelopeCodec.class, () -> mock(ProductionGraphEnvelopeCodec.class));
    context.registerBean(
        ProductionGraphEnvelopeSigner.class, () -> mock(ProductionGraphEnvelopeSigner.class));
    context.registerBean(
        HttpProductionGraphReconciliationClient.class,
        () -> mock(HttpProductionGraphReconciliationClient.class));
    context.registerBean(
        HttpProductionGraphProposalSourceClient.class,
        () -> mock(HttpProductionGraphProposalSourceClient.class));
    context.registerBean(
        GraphRegistryBindingPolicy.class, () -> mock(GraphRegistryBindingPolicy.class));
    context.registerBean(
        ProductionFinalizationRuntimeContextProvider.class,
        () -> mock(ProductionFinalizationRuntimeContextProvider.class));
    context.registerBean(
        ProductionFinalizationActivationPort.class,
        () -> mock(ProductionFinalizationActivationPort.class));
    context.registerBean(MinioClient.class, () -> mock(MinioClient.class));
    context.registerBean(
        PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
  }
}
