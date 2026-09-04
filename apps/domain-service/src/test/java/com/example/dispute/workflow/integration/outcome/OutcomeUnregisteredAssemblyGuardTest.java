package com.example.dispute.workflow.integration.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.application.epoch.ConfiguredRoomEpochSelector;
import com.example.dispute.workflow.config.TemporalWorkerConfiguration;
import com.example.dispute.workflow.contract.v1.ContractTypes;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class OutcomeUnregisteredAssemblyGuardTest {

  private static final String JAVA_MAIN = "apps/domain-service/src/main/java";
  private static final String JAVA_TEST = "apps/domain-service/src/test/java";
  private static final String PYTHON_APP = "apps/agent-runtime/app";
  private static final String OUTCOME_WORKFLOW_SOURCE =
      JAVA_MAIN + "/com/example/dispute/workflow/temporal/room/outcome/OutcomeRoomWorkflow.java";
  private static final String OUTCOME_WORKFLOW_IMPL_SOURCE =
      JAVA_MAIN
          + "/com/example/dispute/workflow/temporal/room/outcome/OutcomeRoomWorkflowImpl.java";
  private static final String OUTCOME_KERNEL_SOURCE =
      JAVA_MAIN + "/com/example/dispute/workflow/temporal/room/outcome/OutcomeWorkflowKernel.java";
  private static final String OUTCOME_PROTOCOL_SOURCE =
      JAVA_MAIN + "/com/example/dispute/workflow/contract/outcome/v1/OutcomeRoomProtocol.java";
  private static final String TARGET_TYPED_DISPATCHER_SOURCE =
      JAVA_MAIN
          + "/com/example/dispute/workflow/runtime/temporal/"
          + "TargetTypedRoomCaseProcessDispatcher.java";
  private static final String TARGET_TYPED_PROTOCOL_SOURCE =
      JAVA_MAIN
          + "/com/example/dispute/workflow/runtime/temporal/TargetTypedRoomProtocol.java";
  private static final String PRODUCTION_RUNTIME_OUTCOME_GRAPH_ADAPTER =
      PYTHON_APP + "/graph_runtime/production_runtime_room_adapters.py";

  private static final List<String> ENGINEERING_BOUNDARY_SOURCES =
      List.of(
          JAVA_MAIN
              + "/com/example/dispute/executor/application/SyntheticNoopExecutionAssembly.java",
          JAVA_MAIN
              + "/com/example/dispute/executor/application/SyntheticOutcomeLedgerAdapter.java",
          JAVA_MAIN
              + "/com/example/dispute/executor/application/SyntheticOutcomeNoopVerticalSlice.java",
          JAVA_MAIN
              + "/com/example/dispute/workflow/activity/tool/SyntheticOutcomeProtocolAdapter.java",
          JAVA_MAIN
              + "/com/example/dispute/workflow/activity/tool/SyntheticNoopCompensationObservation.java",
          JAVA_MAIN
              + "/com/example/dispute/workflow/activity/tool/SyntheticNoopExecutionCommand.java",
          JAVA_MAIN
              + "/com/example/dispute/workflow/activity/tool/SyntheticNoopExecutionReceipt.java",
          JAVA_MAIN + "/com/example/dispute/workflow/activity/tool/SyntheticNoopToolActivity.java",
          JAVA_MAIN
              + "/com/example/dispute/workflow/activity/tool/SyntheticNoopToolActivityImpl.java",
          JAVA_MAIN + "/com/example/dispute/outcome/application/SyntheticOutcomeProjection.java",
          JAVA_MAIN
              + "/com/example/dispute/evaluation/application/OutcomeClosureEvaluationProtocolGate.java",
          JAVA_MAIN
              + "/com/example/dispute/evaluation/application/SyntheticClosedOutcomeSnapshot.java",
          JAVA_MAIN
              + "/com/example/dispute/evaluation/application/SyntheticOutcomeClosureEvaluationService.java");

  private static final Set<String> ALLOWED_OUTCOME_DEFINITION_SOURCES =
      Set.of(
          OUTCOME_WORKFLOW_SOURCE,
          OUTCOME_WORKFLOW_IMPL_SOURCE,
          OUTCOME_KERNEL_SOURCE,
          OUTCOME_PROTOCOL_SOURCE);
  private static final Set<String> ALLOWED_TARGET_OUTCOME_RUNTIME_SOURCES =
      Set.of(TARGET_TYPED_DISPATCHER_SOURCE, TARGET_TYPED_PROTOCOL_SOURCE);

  private static final Pattern OUTCOME_RUNTIME_REFERENCE =
      Pattern.compile(
          "\\b(?:OutcomeRoomWorkflowImpl|OutcomeRoomWorkflow|OutcomeRoomProtocol|"
              + "OutcomeWorkflowKernel)\\b");
  private static final Pattern TEMPORAL_START_CAPABILITY =
      Pattern.compile(
          "\\b(?:WorkflowServiceStubs\\s*\\.\\s*newServiceStubs|"
              + "WorkflowClient\\s*\\.\\s*(?:newInstance|start|execute)|"
              + "newWorkflowStub|newUntypedWorkflowStub|newChildWorkflowStub|"
              + "newUntypedChildWorkflowStub|startWorkflowExecution|startUpdateWithStart)\\s*\\("
              + "|\\b(?:WorkflowClient|Workflow)\\s*::\\s*"
              + "(?:newWorkflowStub|newUntypedWorkflowStub|newChildWorkflowStub|"
              + "newUntypedChildWorkflowStub|start|execute)\\b"
              + "|\\bWorkflowStub\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*(?:=|;)"
              + "|\\b(?=[a-z_$])(?=[A-Za-z0-9_$]*(?i:workflow|temporal|starter|launcher))"
              + "[A-Za-z_$][A-Za-z0-9_$]*\\s*(?:\\.|::)\\s*(?:start|startWorkflow|"
              + "startWorkflowExecution|startUpdateWithStart|launch|execute|run)\\b"
              + "|(?i:\\b(?:stub|client|gateway|wrapper)\\s*(?:\\.|::)\\s*"
              + "(?:start|startWorkflow|startWorkflowExecution|startUpdateWithStart|"
              + "launch)\\b)");
  private static final Pattern GENERIC_TEMPORAL_STARTER_DECLARATION =
      Pattern.compile(
          "\\b[A-Z][A-Za-z0-9_$]*(?:Workflow|Temporal|Starter|Launcher)[A-Za-z0-9_$]*"
              + "(?:\\s*<[^;(){}]*>)?\\s+([a-z_$][A-Za-z0-9_$]*)\\b");
  private static final Pattern ENGINEERING_ACTIVATION_REFERENCE =
      Pattern.compile(
          "\\b(?:SyntheticNoopExecutionAssembly|SyntheticOutcomeNoopVerticalSlice|"
              + "SyntheticOutcomeLedgerAdapter|OutcomeClosureEvaluationProtocolGate|"
              + "SyntheticNoopToolActivityImpl)\\b");
  private static final Pattern OUTCOME_KERNEL_CONSTRUCTION =
      Pattern.compile("\\bnew\\s+OutcomeWorkflowKernel\\s*\\(");
  private static final Pattern OUTCOME_IMPLEMENTATION_CONSTRUCTION =
      Pattern.compile("\\bnew\\s+OutcomeRoomWorkflowImpl\\s*\\(");
  private static final Pattern OUTCOME_IMPLEMENTATION_REGISTRATION =
      Pattern.compile(
          "\\bregisterWorkflowImplementationTypes\\s*\\(\\s*"
              + "OutcomeRoomWorkflowImpl\\s*\\.\\s*class\\b");
  private static final Pattern SYNTHETIC_PUBLIC_ASSEMBLY_CONSTRUCTION =
      Pattern.compile(
          "\\bnew\\s+(?:SyntheticNoopExecutionAssembly|SyntheticOutcomeNoopVerticalSlice|"
              + "SyntheticNoopToolActivityImpl)\\s*\\(");
  private static final Pattern SYNTHETIC_LEDGER_ADAPTER_CONSTRUCTION =
      Pattern.compile("\\bnew\\s+SyntheticOutcomeLedgerAdapter\\s*\\(");

  private static final List<TemporalCapabilityAllowance> ALLOWED_TEMPORAL_CAPABILITIES =
      List.of(
          temporalAllowance(
              JAVA_MAIN + "/com/example/dispute/config/InfrastructureClientConfiguration.java",
              "Temporal service-stub factory",
              "\\bWorkflowServiceStubs\\s*\\.\\s*newServiceStubs\\s*\\(\\s*options\\s*\\)",
              1),
          temporalAllowance(
              JAVA_MAIN + "/com/example/dispute/config/InfrastructureClientConfiguration.java",
              "Temporal client factory",
              "\\bWorkflowClient\\s*\\.\\s*newInstance\\s*\\(\\s*serviceStubs\\s*,"
                  + "\\s*options\\s*\\)",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/application/EvidenceWindowCoordinator.java",
              "typed Evidence window stub",
              "\\bworkflowClient\\s*\\.\\s*newWorkflowStub\\s*\\(\\s*"
                  + "EvidenceWindowWorkflow\\s*\\.\\s*class\\s*,",
              2),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/application/EvidenceWindowCoordinator.java",
              "typed Evidence window start",
              "\\bWorkflowClient\\s*\\.\\s*start\\s*\\(\\s*workflow\\s*::\\s*run\\s*,",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/application/"
                  + "TemporalAgentRunV2WorkflowLauncher.java",
              "typed Agent Run stub",
              "\\bworkflowClient\\s*\\.\\s*newWorkflowStub\\s*\\(\\s*"
                  + "AgentRunWorkflow\\s*\\.\\s*class\\s*,",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/application/"
                  + "TemporalAgentRunV2WorkflowLauncher.java",
              "typed Agent Run start",
              "\\bWorkflowClient\\s*\\.\\s*start\\s*\\(\\s*workflow\\s*::\\s*run\\s*,",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/application/"
                  + "TemporalAgentRunV2WorkflowLauncher.java",
              "existing Agent Run update stub",
              "\\bWorkflowStub\\s+workflow\\s*=\\s*workflowClient\\s*\\.\\s*"
                  + "newUntypedWorkflowStub\\s*\\(\\s*"
                  + "workflowId\\s*\\)",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/infrastructure/bootstrap/"
                  + "SdkRoomEpochProvisioningGateway.java",
              "existing provisioning update-with-start stub",
              "\\bWorkflowStub\\s+workflow\\s*=\\s*workflowClient\\s*\\.\\s*"
                  + "newUntypedWorkflowStub\\s*\\(\\s*"
                  + "request\\s*\\.\\s*workflowType\\s*\\(\\s*\\)\\s*,",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/infrastructure/bootstrap/"
                  + "SdkRoomEpochProvisioningGateway.java",
              "existing provisioning update-with-start invocation",
              "\\bworkflow\\s*\\.\\s*startUpdateWithStart\\s*\\(",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/infrastructure/outbox/"
                  + "SdkTemporalUpdateGateway.java",
              "existing outbox update-with-start stub",
              "\\bWorkflowStub\\s+workflow\\s*=\\s*workflowClient\\s*\\.\\s*"
                  + "newUntypedWorkflowStub\\s*\\(\\s*"
                  + "request\\s*\\.\\s*workflowType\\s*\\(\\s*\\)\\s*,",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/infrastructure/outbox/"
                  + "SdkTemporalUpdateGateway.java",
              "existing outbox update-with-start invocation",
              "\\bworkflow\\s*\\.\\s*startUpdateWithStart\\s*\\(",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/infrastructure/projection/"
                  + "SdkTemporalAuthoritativeProcessStateReader.java",
              "typed Case Process query stub",
              "\\bworkflowClient\\s*\\.\\s*newWorkflowStub\\s*\\(\\s*"
                  + "CaseProcessWorkflow\\s*\\.\\s*class\\s*,",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/infrastructure/recovery/"
                  + "CaseDomainEventRecoveryRelay.java",
              "typed Case Process recovery stub",
              "\\bworkflowClient\\s*\\.\\s*newWorkflowStub\\s*\\(\\s*"
                  + "CaseProcessWorkflow\\s*\\.\\s*class\\s*,",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/temporal/caseprocess/"
                  + "CaseProcessWorkflowImpl.java",
              "typed Room Control child stub",
              "\\bWorkflow\\s*\\.\\s*newChildWorkflowStub\\s*\\(\\s*"
                  + "RoomControlWorkflow\\s*\\.\\s*class\\s*,",
              2),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/temporal/caseprocess/"
                  + "CaseProcessWorkflowImpl.java",
              "typed Intake child stub",
              "\\bWorkflow\\s*\\.\\s*newChildWorkflowStub\\s*\\(\\s*"
                  + "IntakeRoomWorkflow\\s*\\.\\s*class\\s*,",
              1),
          temporalAllowance(
              JAVA_MAIN + "/com/example/dispute/agentstream/application/AgentRunV2Coordinator.java",
              "Agent Run workflow launcher",
              "\\bworkflowLauncher\\s*\\.\\s*start\\s*\\(\\s*request\\s*\\)",
              1),
          temporalAllowance(
              TARGET_TYPED_DISPATCHER_SOURCE,
              "production-only Intake child stub",
              "\\bWorkflow\\s*\\.\\s*newChildWorkflowStub\\s*\\(\\s*"
                  + "IntakeRoomWorkflow\\s*\\.\\s*class\\s*,",
              1),
          temporalAllowance(
              TARGET_TYPED_DISPATCHER_SOURCE,
              "production-only Evidence child stub",
              "\\bWorkflow\\s*\\.\\s*newChildWorkflowStub\\s*\\(\\s*"
                  + "EvidenceRoomWorkflow\\s*\\.\\s*class\\s*,",
              1),
          temporalAllowance(
              TARGET_TYPED_DISPATCHER_SOURCE,
              "production-only Hearing child stub",
              "\\bWorkflow\\s*\\.\\s*newChildWorkflowStub\\s*\\(\\s*"
                  + "HearingRoomWorkflow\\s*\\.\\s*class\\s*,",
              1),
          temporalAllowance(
              TARGET_TYPED_DISPATCHER_SOURCE,
              "production-only Review/Outcome child stub",
              "\\bWorkflow\\s*\\.\\s*newChildWorkflowStub\\s*\\(\\s*"
                  + "OutcomeRoomWorkflow\\s*\\.\\s*class\\s*,",
              1),
          temporalAllowance(
              TARGET_TYPED_DISPATCHER_SOURCE,
              "production-only Agent Run child stub",
              "\\bWorkflow\\s*\\.\\s*newChildWorkflowStub\\s*\\(\\s*"
                  + "AgentRunWorkflow\\s*\\.\\s*class\\s*,",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/temporal/room/hearing/"
                  + "HearingRoomWorkflowImpl.java",
              "target Hearing Agent Run child stub",
              "\\bWorkflow\\s*\\.\\s*newChildWorkflowStub\\s*\\(\\s*"
                  + "AgentRunWorkflow\\s*\\.\\s*class\\s*,",
              1),
          temporalAllowance(
              JAVA_MAIN
                  + "/com/example/dispute/workflow/temporal/room/intake/"
                  + "IntakeRoomWorkflowImpl.java",
              "target Intake Agent Run child stub",
              "\\bWorkflow\\s*\\.\\s*newChildWorkflowStub\\s*\\(\\s*"
                  + "AgentRunWorkflow\\s*\\.\\s*class\\s*,",
              1));

  private static final List<ForbiddenPattern> FORBIDDEN_PYTHON_ASSEMBLY =
      List.of(
          new ForbiddenPattern(
              "dynamic module import",
              Pattern.compile(
                  "(?m)^\\s*from\\s+importlib\\s+import\\b|"
                      + "(?m)^\\s*import\\s+importlib(?:\\s|$)|"
                      + "\\bimportlib\\s*\\.\\s*(?:import_module|reload|util|machinery)\\b|"
                      + "\\b(?:__import__|import_module)\\b|"
                      + "\\b(?:__builtins__|builtins)\\s*(?:\\.|\\[)")),
          new ForbiddenPattern(
              "dynamic module spec or loader",
              Pattern.compile(
                  "\\b(?:spec_from_file_location|module_from_spec|exec_module|load_module|"
                      + "SourceFileLoader|SourcelessFileLoader|ExtensionFileLoader|ModuleSpec|"
                      + "zipimporter)\\b")),
          new ForbiddenPattern(
              "dynamic code execution",
              Pattern.compile(
                  "\\bbuiltins\\s*\\.\\s*(?:exec|eval|compile)\\b|"
                      + "(?<![A-Za-z0-9_$.])(?:exec|eval|compile)\\s*\\(")),
          new ForbiddenPattern(
              "dynamic graph builder lookup",
              Pattern.compile(
                  "(?is)\\bgetattr\\s*\\([^)]{0,500}\\)\\s*\\(|"
                      + "\\bgetattr\\s*\\([^)]{0,400}(?:build|compile|import|module)"
                      + "[A-Za-z0-9_+.'\"\\s-]{0,240}\\)")),
          new ForbiddenPattern(
              "Outcome graph import",
              Pattern.compile(
                  "(?im)^\\s*(?:from\\s+app\\.graphs\\.outcome(?:\\.|\\s)|"
                      + "from\\s+app\\.graphs\\s+import\\s+[^\\n]*\\boutcome\\b|"
                      + "import\\s+app\\.graphs\\.outcome\\b)")),
          new ForbiddenPattern(
              "Outcome graph module literal",
              Pattern.compile("(?i)['\"]app\\.graphs\\.outcome(?:\\.[A-Za-z0-9_]+)*['\"]")),
          new ForbiddenPattern(
              "Outcome graph runtime symbol",
              Pattern.compile(
                  "(?i)\\b(?:(?:build|compile|invoke)_outcome_review(?:_v1)?(?:_graph|_lcel|"
                      + "_graph_session)?|OutcomeReviewGraphSession|OUTCOME_REVIEW_(?:GRAPH_)?"
                      + "IDENTITY)\\b")),
          new ForbiddenPattern(
              "Outcome graph identity binding",
              Pattern.compile(
                  "(?i)\\b(?:graph_key|graph_version|graph_identity|graph_id|identity)\\s*=\\s*"
                      + "['\"]outcome(?:[./_-]review)(?:[./_-]v1)?['\"]")),
          new ForbiddenPattern(
              "Outcome graph registry binding",
              Pattern.compile(
                  "(?is)\\b(?:registry|bindings?|builders?|graphs?|lifecycles?|commands?|"
                      + "dispatchers?)\\s*\\[\\s*['\"]outcome(?:[./_-]review)"
                      + "(?:[./_-]v1)?['\"]\\s*]\\s*=")),
          new ForbiddenPattern(
              "Outcome graph registry call",
              Pattern.compile(
                  "(?is)\\b(?:register|resolve|select|get|load|compile|invoke|dispatch|start|"
                      + "submit|bind|add)[A-Za-z0-9_]*"
                      + "\\s*\\([^)]{0,240}['\"]outcome(?:[./_-]review)"
                      + "(?:[./_-]v1)?['\"]")));

  private static final List<ForbiddenPattern> FORBIDDEN_ENGINEERING_CAPABILITIES =
      List.of(
          new ForbiddenPattern(
              "Spring or Temporal import",
              Pattern.compile("(?m)^\\s*import\\s+(?:org\\.springframework|io\\.temporal)\\.")),
          new ForbiddenPattern(
              "Spring or Temporal fully qualified annotation",
              Pattern.compile("@\\s*(?:org\\.springframework|io\\.temporal)\\.")),
          new ForbiddenPattern(
              "non-language annotation",
              Pattern.compile(
                  "@\\s*(?!(?:Override|FunctionalInterface)\\b)" + "[A-Za-z_$][A-Za-z0-9_$.]*")),
          new ForbiddenPattern(
              "production tool import",
              Pattern.compile("(?m)^\\s*import\\s+com\\.example\\.dispute\\.tool\\.")),
          new ForbiddenPattern(
              "real executor or tool type",
              Pattern.compile(
                  "\\b(?:ToolExecutorService|ExecutionController|ExecutionBatchView|"
                      + "ActionExecutionLock|RedisActionExecutionLock|ToolRegistry|ToolAdapter|"
                      + "ToolDefinition|SimulatedExecutionTool|InternalToolCatalogController|"
                      + "ExecutableAction|ToolExecutionResult)\\b")),
          new ForbiddenPattern(
              "real executor or tool entry point",
              Pattern.compile(
                  "\\b(?:executeApprovedActions|executeAction|executionTools)\\b|"
                      + "\\b(?:toolRegistry|toolAdapter|executionTool|executorService|"
                      + "toolExecutor)\\s*(?:\\.|::)\\s*(?:execute|definitions|supports)\\b|"
                      + "\\b[A-Za-z_$][A-Za-z0-9_$]*(?:Tool|Executor|Execution)"
                      + "[A-Za-z0-9_$]*\\s*::\\s*(?:execute|executeAction|"
                      + "executeApprovedActions|definitions|supports)\\b")),
          new ForbiddenPattern(
              "network client or transport",
              Pattern.compile(
                  "(?m)^\\s*import\\s+(?:java\\.net|java\\.net\\.http|"
                      + "org\\.springframework\\.web|okhttp3|retrofit2|feign)\\."
                      + "|\\b(?:java\\.net(?:\\.http)?|org\\.springframework\\.web|okhttp3|"
                      + "retrofit2|feign)\\."
                      + "|\\b(?:RestTemplate|RestClient|WebClient|HttpClient|HttpRequest|"
                      + "HttpResponse|HttpURLConnection|URLConnection|OkHttpClient|Socket|"
                      + "ServerSocket|DatagramSocket|SocketChannel|AsynchronousSocketChannel|"
                      + "NetworkChannel|URI|URL|InetAddress)\\b|\\b[A-Za-z_$][A-Za-z0-9_$]*Transport"
                      + "[A-Za-z0-9_$]*\\b")),
          new ForbiddenPattern(
              "credential-bearing type",
              Pattern.compile(
                  "(?i)\\b(?:[A-Za-z0-9_$]*(?:Credential|Credentials)[A-Za-z0-9_$]*"
                      + "Provider|[A-Za-z0-9_$]*Secret[A-Za-z0-9_$]*|"
                      + "(?:Credential|Credentials|ApiKey|Password|BearerToken|"
                      + "AccessToken|ClientSecret|PrivateKey|SecretKey|KeyStore|KeyManager|"
                      + "TrustManager)[A-Za-z0-9_$]*)\\b")),
          new ForbiddenPattern(
              "reflection or dynamic invocation",
              Pattern.compile(
                  "(?m)^\\s*import\\s+java\\.lang\\.(?:reflect|invoke)\\.|"
                      + "\\bjava\\.lang\\.(?:reflect|invoke)\\.|"
                      + "\\b(?:Class\\s*\\.\\s*forName|ServiceLoader\\s*\\.\\s*load|"
                      + "MethodHandle|MethodHandles|MethodType|VarHandle|LambdaMetafactory|"
                      + "ClassLoader|AccessibleObject|Proxy\\s*\\.\\s*newProxyInstance|"
                      + "ScriptEngine|GroovyShell)\\b|\\.\\s*(?:getMethod|getDeclaredMethod|"
                      + "getDeclaredConstructor|setAccessible|invoke|invokeExact|newInstance)"
                      + "\\s*(?:\\(|\\b)")),
          new ForbiddenPattern("formal receipt write", Pattern.compile("\\brecordReceipt\\b")),
          new ForbiddenPattern(
              "formal sink import",
              Pattern.compile(
                  "(?mi)^\\s*import\\s+[^;]*(?:FormalSink|FormalWriter|"
                      + "Finalizer|Committer)\\s*;")),
          new ForbiddenPattern(
              "formal sink type",
              Pattern.compile(
                  "\\b(?:FormalSink|FormalWriter|OutcomeFinalizer|OutcomeCommitter|"
                      + "OutcomeClosureWriter)\\b")),
          new ForbiddenPattern(
              "formal sink invocation",
              Pattern.compile(
                  "(?i)\\b(?:commitOutcome|commitClosure|finalizeOutcome|"
                      + "writeFormal|publishFormal)\\s*\\(")));

  @Test
  void temporalWorkerConfigurationDoesNotRegisterOutcomeWorkflow() {
    String source = javaStructure(javaCode(read(temporalWorkerConfigurationSource())));
    String bytecode = classFileText(TemporalWorkerConfiguration.class);

    assertThat(source)
        .as("Temporal worker source must not know the unregistered Outcome workflow")
        .doesNotContain("OutcomeRoomWorkflow", "OutcomeRoomWorkflowImpl");
    assertThat(bytecode)
        .as("compiled Temporal worker must not contain Outcome workflow class constants")
        .doesNotContain("OutcomeRoomWorkflow", "OutcomeRoomWorkflowImpl");
  }

  @Test
  void selectorsAndRoomTypesCannotAllocateOutcome() {
    assertThat(Arrays.stream(ContractTypes.RoomType.values()).map(Enum::name))
        .as("workflow RoomType remains closed without OUTCOME")
        .doesNotContain("OUTCOME");
    assertThat(Arrays.stream(com.example.dispute.room.domain.RoomType.values()).map(Enum::name))
        .as("domain RoomType remains closed without OUTCOME")
        .doesNotContain("OUTCOME");

    String selectorSource = javaStructure(javaCode(read(configuredRoomEpochSelectorSource())));
    assertThat(selectorSource.toLowerCase(Locale.ROOT))
        .as("ConfiguredRoomEpochSelector must not contain an Outcome allocation branch")
        .doesNotContain("outcome");
    assertThat(classFileText(ConfiguredRoomEpochSelector.class))
        .as("compiled selector must not carry an Outcome allocation constant")
        .doesNotContain("OUTCOME");
  }

  @Test
  void productionHasOnlyAllowlistedTemporalStarterCapabilitiesAndNoOutcomeAssemblyReference() {
    List<String> violations = new ArrayList<>();
    Set<String> engineeringSources = Set.copyOf(ENGINEERING_BOUNDARY_SOURCES);

    for (Path source : javaSources(repositoryRoot().resolve(JAVA_MAIN))) {
      String relative = relative(source);
      String content = read(source);
      String code = javaCode(content);
      String structure = javaStructure(code);
      if (!ALLOWED_OUTCOME_DEFINITION_SOURCES.contains(relative)
          && !ALLOWED_TARGET_OUTCOME_RUNTIME_SOURCES.contains(relative)) {
        addMatches(violations, relative, structure, OUTCOME_RUNTIME_REFERENCE);
      }
      violations.addAll(temporalStarterViolations(relative, content));
      if (!engineeringSources.contains(relative)) {
        addMatches(violations, relative, structure, ENGINEERING_ACTIVATION_REFERENCE);
      }
    }

    assertThat(violations)
        .as(
            "Outcome definitions may exist, but production Temporal start capability is closed"
                + " to exact reviewed sites and cannot reach Outcome assembly")
        .isEmpty();
  }

  @Test
  void syntheticEngineeringBoundaryHasNoRuntimeOrFormalCapability() {
    assertThat(ENGINEERING_BOUNDARY_SOURCES)
        .as("the guarded E boundary is an explicit closed source set")
        .allSatisfy(path -> assertThat(repositoryRoot().resolve(path)).isRegularFile());

    List<String> sourceViolations = new ArrayList<>();
    for (String relative : ENGINEERING_BOUNDARY_SOURCES) {
      sourceViolations.addAll(engineeringCapabilityViolations(relative, read(relative)));
    }

    assertThat(sourceViolations)
        .as(
            "synthetic Outcome code must have no Spring/Temporal assembly, real tool, network,"
                + " credential, recordReceipt, or formal sink capability")
        .isEmpty();
  }

  @Test
  void engineeringBoundaryRulesRejectEveryRealExecutorAndToolEntrySymbol() {
    for (String symbol :
        List.of(
            "ToolExecutorService",
            "ExecutionController",
            "ExecutionBatchView",
            "ActionExecutionLock",
            "RedisActionExecutionLock",
            "ToolRegistry",
            "ToolAdapter",
            "ToolDefinition",
            "SimulatedExecutionTool",
            "InternalToolCatalogController",
            "ExecutableAction",
            "ToolExecutionResult")) {
      assertThat(
              engineeringCapabilityViolations(
                  "mutation/" + symbol + ".java", "class Mutation { " + symbol + " value; }"))
          .as("the E-boundary guard must reject real executor/tool symbol %s", symbol)
          .isNotEmpty();
    }

    for (MutationFixture mutation :
        List.of(
            new MutationFixture(
                "execute-approved-actions",
                "class Mutation { void run(Object executorService) { "
                    + "executorService.executeApprovedActions(null, null, null); } }"),
            new MutationFixture(
                "tool-registry-execute",
                "class Mutation { void run(Object toolRegistry) { toolRegistry.execute(null); } }"),
            new MutationFixture(
                "real executor method reference",
                "class Mutation { Object run(ToolExecutorService executor) { "
                    + "return executor::executeApprovedActions; } }"),
            new MutationFixture(
                "tool-catalog-entry",
                "class Mutation { void run(Object catalog) { executionTools(); } }"))) {
      assertThat(engineeringCapabilityViolations("mutation.java", mutation.source()))
          .as("the E-boundary guard must reject %s", mutation.description())
          .isNotEmpty();
    }
  }

  @Test
  void engineeringBoundaryRulesRejectReceiptCredentialTransportAndDynamicBypasses() {
    List<MutationFixture> mutations =
        List.of(
            new MutationFixture(
                "receipt method reference",
                "class Mutation { Object writer(Object ledger) { return ledger::recordReceipt; }"
                    + " }"),
            new MutationFixture(
                "SecretKey", "class Mutation { javax.crypto.SecretKey secretKey; }"),
            new MutationFixture("KeyStore", "class Mutation { java.security.KeyStore keyStore; }"),
            new MutationFixture(
                "credential provider", "class Mutation { AwsCredentialsProvider provider; }"),
            new MutationFixture(
                "secret manager", "class Mutation { OutcomeSecretManager secretManager; }"),
            new MutationFixture(
                "java.net", "import java.net.URI; class Mutation { URI endpoint; }"),
            new MutationFixture("URL", "class Mutation { URL endpoint; }"),
            new MutationFixture(
                "fully qualified java.net",
                "class Mutation { Object endpoint = java.net.URI.create(value); }"),
            new MutationFixture(
                "java.net.http", "import java.net.http.HttpClient; class Mutation {}"),
            new MutationFixture("OkHttp", "class Mutation { okhttp3.OkHttpClient client; }"),
            new MutationFixture("RestTemplate", "class Mutation { RestTemplate client; }"),
            new MutationFixture("WebClient", "class Mutation { WebClient client; }"),
            new MutationFixture("socket", "class Mutation { ServerSocket socket; }"),
            new MutationFixture(
                "transport", "class Mutation { JdkOutcomeHttpTransport transport; }"),
            new MutationFixture(
                "reflection", "class Mutation { Class<?> type = Class.forName(name); }"),
            new MutationFixture(
                "fully qualified reflection",
                "class Mutation { java.lang.reflect.Method method; }"),
            new MutationFixture(
                "method handle", "class Mutation { java.lang.invoke.MethodHandle handle; }"),
            new MutationFixture(
                "reflection access override",
                "class Mutation { void run() { field.setAccessible(true); } }"),
            new MutationFixture(
                "dynamic invocation", "class Mutation { Object value = method.invoke(target); }"));

    for (MutationFixture mutation : mutations) {
      assertThat(engineeringCapabilityViolations("mutation.java", mutation.source()))
          .as("the E-boundary guard must reject %s", mutation.description())
          .isNotEmpty();
    }
  }

  @Test
  void pythonProductionModulesDoNotActivateOutcomeGraph() {
    List<String> violations = new ArrayList<>();
    for (Path source : pythonSources(repositoryRoot().resolve(PYTHON_APP))) {
      String relative = relative(source);
      violations.addAll(pythonAssemblyViolations(relative, read(source)));
    }

    assertThat(violations)
        .as(
            "the private outcome.review.v1 graph may exist only under app/graphs/outcome; every"
                + " other production import, binding, lifecycle, or command dispatch is forbidden")
        .isEmpty();
  }

  @Test
  void pythonProductionScanExcludesOnlyTheOutcomeDefinitionSubtree() {
    Path appRoot = repositoryRoot().resolve(PYTHON_APP);
    Path outcomeDefinitions = appRoot.resolve("graphs/outcome").toAbsolutePath().normalize();
    List<Path> scanned = pythonSources(appRoot);

    for (Path source : allPythonSources(appRoot)) {
      if (source.toAbsolutePath().normalize().startsWith(outcomeDefinitions)) {
        assertThat(scanned)
            .as("Outcome graph definitions are not their own activation surface")
            .doesNotContain(source);
      } else {
        assertThat(scanned)
            .as("every non-definition production Python module is guarded")
            .contains(source);
      }
    }
  }

  @Test
  void pythonAssemblyRulesRejectImportBindingLifecycleAndCommandDispatchBypasses() {
    List<MutationFixture> mutations =
        List.of(
            new MutationFixture(
                "direct import", "from app.graphs.outcome.graph import build_outcome_review_graph"),
            new MutationFixture(
                "dynamic import", "importlib.import_module('app.graphs.outcome.runtime')"),
            new MutationFixture(
                "computed builtin import and builder lookup",
                "module = __import__('app.graphs.'+'outcome.graph', "
                    + "fromlist=['build_'+'outcome_review_graph'])\n"
                    + "getattr(module, 'build_'+'outcome_review_graph')()"),
            new MutationFixture(
                "computed importlib import",
                "module_name = 'app.graphs.' + graph_family\n"
                    + "module = importlib.import_module(module_name)"),
            new MutationFixture(
                "aliased importlib attribute lookup",
                "import importlib as loader\n"
                    + "load = getattr(loader, 'import_' + 'module')\n"
                    + "module = load(module_name)"),
            new MutationFixture(
                "computed builtins import lookup",
                "load = __builtins__['__' + 'import__']\nmodule = load(module_name)"),
            new MutationFixture(
                "module spec loader",
                "spec = importlib.util.spec_from_file_location(name, path)\n"
                    + "module = importlib.util.module_from_spec(spec)\n"
                    + "spec.loader.exec_module(module)"),
            new MutationFixture("dynamic exec", "code = compile(source, path, 'exec')\nexec(code)"),
            new MutationFixture("dynamic eval", "builder = eval(builder_expression)"),
            new MutationFixture(
                "production binding",
                "PRODUCTION_BUILDERS['outcome.review.v1'] = build_outcome_review_graph"),
            new MutationFixture(
                "lifecycle registration",
                "lifecycle.register_graph(identity='outcome.review.v1', builder=handler)"),
            new MutationFixture(
                "command dispatch", "command_bus.dispatch('outcome.review.v1', command)"));

    for (MutationFixture mutation : mutations) {
      assertThat(pythonAssemblyViolations("mutation.py", mutation.source()))
          .as("the Python production guard must reject %s", mutation.description())
          .isNotEmpty();
    }
  }

  @Test
  void pythonProtocolOnlyOutcomeLanguageDoesNotActivateTheGraph() {
    String protocolOnly =
        "POLICY = {'risk_policy': 'outcome-review-advisory-only-v1'}\n"
            + "requested_outcome = request.requested_outcome\n";

    assertThat(pythonAssemblyViolations("protocol.py", protocolOnly))
        .as("non-activating business/protocol Outcome language remains allowed")
        .isEmpty();
  }

  @Test
  void starterRulesRejectNewTypedUntypedLowLevelGenericAndDistributedCapabilities() {
    List<MutationFixture> mutations =
        List.of(
            new MutationFixture(
                "typed start",
                "class Mutation { void run(WorkflowClient client) { var workflow = "
                    + "client.newWorkflowStub(OtherWorkflow.class, options); "
                    + "WorkflowClient.start(workflow::run); } }"),
            new MutationFixture(
                "untyped start without an Outcome token",
                "class Mutation { void run(WorkflowClient client) { WorkflowStub workflow = "
                    + "client.newUntypedWorkflowStub(workflowType, options); "
                    + "workflow.start(payload); } }"),
            new MutationFixture(
                "low-level service start without an Outcome token",
                "class Mutation { void run(WorkflowServiceStubs service) { "
                    + "service.blockingStub().startWorkflowExecution("
                    + "StartWorkflowExecutionRequest.newBuilder()"
                    + ".setWorkflowType(workflowType).build()); } }"),
            new MutationFixture(
                "generic wrapper start without an Outcome token",
                "class Mutation { void run(GenericWorkflowStarter starter) { "
                    + "starter.start(workflowType, payload); } }"),
            new MutationFixture(
                "split workflow type",
                "class Mutation { void run(WorkflowClient client) { "
                    + "client.newUntypedWorkflowStub(\"Outcome\" + \"RoomWorkflow\", options)"
                    + ".start(payload); } }"),
            new MutationFixture(
                "computed workflow type",
                "class Mutation { void run(WorkflowClient client) { String workflowType = "
                    + "prefix + suffix; client.newUntypedWorkflowStub(workflowType, options); } }"),
            new MutationFixture(
                "cross-file generic starter",
                "class Dispatch { void run(GenericWorkflowStarter starter, String workflowType) "
                    + "{ starter.start(workflowType, payload); } }"),
            new MutationFixture(
                "generic starter method reference",
                "class Dispatch { Object run(GenericWorkflowStarter gateway) { "
                    + "return gateway::start; } }"),
            new MutationFixture(
                "Temporal client method reference",
                "class Dispatch { Object run() { return WorkflowClient::start; } }"),
            new MutationFixture(
                "cross-file untyped stub",
                "class Dispatch { WorkflowStub workflow; void run() { workflow.start(payload); "
                    + "} }"));

    for (MutationFixture mutation : mutations) {
      assertThat(temporalStarterViolations("mutation.java", mutation.source()))
          .as("the Temporal starter guard must reject %s", mutation.description())
          .isNotEmpty();
    }
  }

  @Test
  void starterAllowlistRejectsWrongRoomInterfaceAndAllowsOrdinaryDomainStarts() {
    String caseProcessSource =
        JAVA_MAIN
            + "/com/example/dispute/workflow/temporal/caseprocess/CaseProcessWorkflowImpl.java";
    String original = read(caseProcessSource);
    String wrongRoomInterface =
        original.replace("RoomControlWorkflow.class", "UnexpectedRoomWorkflow.class");

    assertThat(wrongRoomInterface).isNotEqualTo(original);
    assertThat(temporalStarterViolations(caseProcessSource, wrongRoomInterface))
        .as("typed room starts are limited to the reviewed Room Control and Intake interfaces")
        .isNotEmpty();

    String wrongCallForm =
        read(JAVA_MAIN
                + "/com/example/dispute/workflow/application/"
                + "EvidenceWindowCoordinator.java")
            .replace("WorkflowClient.start(", "WorkflowClient.execute(");
    assertThat(
            temporalStarterViolations(
                JAVA_MAIN
                    + "/com/example/dispute/workflow/application/"
                    + "EvidenceWindowCoordinator.java",
                wrongCallForm))
        .as("reviewed Temporal starter calls must retain their exact allowed form")
        .isNotEmpty();

    String ordinaryDomainStarts =
        "class DomainService { void run() { aggregate.start(command); "
            + "HearingStateEntity.start(caseId, now); process.begin(command); } }";
    assertThat(temporalStarterViolations("domain.java", ordinaryDomainStarts))
        .as("ordinary domain start methods are not Temporal starter capabilities")
        .isEmpty();
  }

  @Test
  void onlyTestsAssembleTheOutcomeImplementationAndDirectlyExerciseItsKernel() {
    List<String> productionViolations = new ArrayList<>();
    List<String> productionKernelOwners = new ArrayList<>();
    for (Path source : javaSources(repositoryRoot().resolve(JAVA_MAIN))) {
      String relative = relative(source);
      String structure = javaStructure(javaCode(read(source)));
      if (OUTCOME_KERNEL_CONSTRUCTION.matcher(structure).find()) {
        productionKernelOwners.add(relative);
        if (!OUTCOME_WORKFLOW_IMPL_SOURCE.equals(relative)) {
          addMatches(productionViolations, relative, structure, OUTCOME_KERNEL_CONSTRUCTION);
        }
      }
      addMatches(productionViolations, relative, structure, OUTCOME_IMPLEMENTATION_CONSTRUCTION);
      addMatches(productionViolations, relative, structure, OUTCOME_IMPLEMENTATION_REGISTRATION);
    }

    assertThat(productionViolations)
        .as("production must neither instantiate nor register the unregistered Outcome workflow")
        .isEmpty();
    assertThat(productionKernelOwners)
        .as("the package-private kernel is owned only by the unregistered workflow definition")
        .containsExactly(OUTCOME_WORKFLOW_IMPL_SOURCE);

    List<String> testKernelConstructors = new ArrayList<>();
    List<String> testRegistrations = new ArrayList<>();
    for (Path source : javaSources(repositoryRoot().resolve(JAVA_TEST))) {
      String relative = relative(source);
      String structure = javaStructure(javaCode(read(source)));
      addMatches(testKernelConstructors, relative, structure, OUTCOME_KERNEL_CONSTRUCTION);
      addMatches(testRegistrations, relative, structure, OUTCOME_IMPLEMENTATION_REGISTRATION);
    }
    assertThat(testKernelConstructors)
        .as("direct Outcome kernel construction must remain test-only")
        .isNotEmpty();
    assertThat(testRegistrations)
        .as("Outcome workflow implementation registration must remain test-only")
        .isNotEmpty();
  }

  @Test
  void syntheticVerticalSliceIsInstantiatedOnlyByItsTestHarness() {
    List<String> productionViolations = new ArrayList<>();
    List<String> adapterOwners = new ArrayList<>();
    for (Path source : javaSources(repositoryRoot().resolve(JAVA_MAIN))) {
      String relative = relative(source);
      String structure = javaStructure(javaCode(read(source)));
      addMatches(productionViolations, relative, structure, SYNTHETIC_PUBLIC_ASSEMBLY_CONSTRUCTION);
      if (SYNTHETIC_LEDGER_ADAPTER_CONSTRUCTION.matcher(structure).find()) {
        adapterOwners.add(relative);
        if (!relative.endsWith("/SyntheticOutcomeNoopVerticalSlice.java")) {
          addMatches(
              productionViolations, relative, structure, SYNTHETIC_LEDGER_ADAPTER_CONSTRUCTION);
        }
      }
    }
    assertThat(productionViolations)
        .as("production must not instantiate the unregistered synthetic Outcome assemblies")
        .isEmpty();
    assertThat(adapterOwners)
        .as("the package-private ledger adapter is owned only by the vertical slice")
        .containsExactly(
            JAVA_MAIN
                + "/com/example/dispute/executor/application/"
                + "SyntheticOutcomeNoopVerticalSlice.java");

    String syntheticHarness =
        javaStructure(
            javaCode(
                read(
                    JAVA_TEST
                        + "/com/example/dispute/executor/OutcomeSyntheticNoopAssemblyTest.java")));
    assertThat(syntheticHarness)
        .containsPattern("\\bnew\\s+SyntheticNoopExecutionAssembly\\s*\\(")
        .containsPattern("\\bnew\\s+SyntheticOutcomeNoopVerticalSlice\\s*\\(")
        .containsPattern("\\bnew\\s+SyntheticNoopToolActivityImpl\\s*\\(");
  }

  private static Path temporalWorkerConfigurationSource() {
    return repositoryRoot()
        .resolve(
            JAVA_MAIN + "/com/example/dispute/workflow/config/TemporalWorkerConfiguration.java");
  }

  private static Path configuredRoomEpochSelectorSource() {
    return repositoryRoot()
        .resolve(
            JAVA_MAIN
                + "/com/example/dispute/workflow/application/epoch/ConfiguredRoomEpochSelector.java");
  }

  private static String classFileText(Class<?> type) {
    String resource = "/" + type.getName().replace('.', '/') + ".class";
    try (InputStream input = type.getResourceAsStream(resource)) {
      assertThat(input).as("compiled class resource %s", resource).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot read " + resource, failure);
    }
  }

  private static List<Path> javaSources(Path root) {
    assertThat(root).as("source root %s", root).isDirectory();
    try (Stream<Path> sources = Files.walk(root)) {
      return sources
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .sorted()
          .toList();
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot scan " + root, failure);
    }
  }

  private static List<Path> pythonSources(Path root) {
    Path outcomeDefinitions = root.resolve("graphs/outcome").toAbsolutePath().normalize();
    return allPythonSources(root).stream()
        .filter(path -> !path.toAbsolutePath().normalize().startsWith(outcomeDefinitions))
        .toList();
  }

  private static List<Path> allPythonSources(Path root) {
    assertThat(root).as("source root %s", root).isDirectory();
    try (Stream<Path> sources = Files.walk(root)) {
      return sources
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".py"))
          .sorted()
          .toList();
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot scan " + root, failure);
    }
  }

  private static List<String> engineeringCapabilityViolations(String source, String content) {
    return forbiddenViolations(
        source, javaStructure(javaCode(content)), FORBIDDEN_ENGINEERING_CAPABILITIES);
  }

  private static List<String> pythonAssemblyViolations(String source, String content) {
    List<ForbiddenPattern> patterns = FORBIDDEN_PYTHON_ASSEMBLY;
    if (PRODUCTION_RUNTIME_OUTCOME_GRAPH_ADAPTER.equals(source)) {
      patterns =
          patterns.stream()
              .filter(pattern -> !pattern.description().equals("Outcome graph import"))
              .toList();
    }
    return forbiddenViolations(source, pythonCode(content), patterns);
  }

  private static List<String> forbiddenViolations(
      String source, String content, List<ForbiddenPattern> forbiddenPatterns) {
    List<String> violations = new ArrayList<>();
    for (ForbiddenPattern forbidden : forbiddenPatterns) {
      addMatches(
          violations, source + " [" + forbidden.description() + "]", content, forbidden.pattern());
    }
    return violations;
  }

  private static TemporalCapabilityAllowance temporalAllowance(
      String source, String description, String allowedForm, int expectedCount) {
    return new TemporalCapabilityAllowance(
        source, description, Pattern.compile(allowedForm), expectedCount);
  }

  private static List<String> temporalStarterViolations(String source, String content) {
    String structure = javaStructure(javaCode(content));
    List<String> violations = new ArrayList<>();
    List<SourceSpan> allowedSpans = new ArrayList<>();
    for (TemporalCapabilityAllowance allowance : ALLOWED_TEMPORAL_CAPABILITIES) {
      if (!allowance.source().equals(source)) {
        continue;
      }
      Matcher allowed = allowance.allowedForm().matcher(structure);
      int count = 0;
      while (allowed.find()) {
        count++;
        allowedSpans.add(new SourceSpan(allowed.start(), allowed.end()));
      }
      if (count != allowance.expectedCount()) {
        violations.add(
            source
                + " -> Temporal capability allowlist drift for "
                + allowance.description()
                + ": expected "
                + allowance.expectedCount()
                + " exact form(s), found "
                + count);
      }
    }

    Matcher capability = TEMPORAL_START_CAPABILITY.matcher(structure);
    while (capability.find()) {
      int start = capability.start();
      int end = capability.end();
      boolean allowed =
          allowedSpans.stream().anyMatch(span -> span.start() <= start && span.end() >= end);
      if (!allowed) {
        violations.add(
            source
                + ":"
                + lineNumber(structure, start)
                + " -> unallowlisted Temporal start capability "
                + capability.group().replaceAll("\\s+", " ").trim());
      }
    }
    Matcher declaration = GENERIC_TEMPORAL_STARTER_DECLARATION.matcher(structure);
    while (declaration.find()) {
      String starter = declaration.group(1);
      Pattern invocation =
          Pattern.compile(
              "\\b"
                  + Pattern.quote(starter)
                  + "\\s*(?:\\.|::)\\s*(?:start|startWorkflow|startWorkflowExecution|"
                  + "startUpdateWithStart|launch|execute)\\b");
      Matcher genericCapability = invocation.matcher(structure);
      while (genericCapability.find()) {
        int start = genericCapability.start();
        boolean allowed =
            allowedSpans.stream().anyMatch(span -> span.start() <= start && span.end() >= start);
        if (!allowed) {
          violations.add(
              source
                  + ":"
                  + lineNumber(structure, start)
                  + " -> unallowlisted generic Temporal starter capability "
                  + genericCapability.group().replaceAll("\\s+", " ").trim());
        }
      }
    }
    return violations;
  }

  private static void addMatches(
      List<String> violations, String source, String content, Pattern pattern) {
    Matcher matcher = pattern.matcher(content);
    while (matcher.find()) {
      violations.add(
          source
              + ":"
              + lineNumber(content, matcher.start())
              + " -> "
              + matcher.group().replaceAll("\\s+", " ").trim());
    }
  }

  private static int lineNumber(String content, int offset) {
    int line = 1;
    for (int index = 0; index < offset; index++) {
      if (content.charAt(index) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static String read(String relative) {
    return read(repositoryRoot().resolve(relative));
  }

  private static String read(Path path) {
    assertThat(path).as("guarded source %s", path).isRegularFile();
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot read " + path, failure);
    }
  }

  private static String relative(Path path) {
    return repositoryRoot()
        .relativize(path.toAbsolutePath().normalize())
        .toString()
        .replace('\\', '/');
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("apps/domain-service/src/main/java"))
          && Files.isDirectory(current.resolve("apps/agent-runtime/app"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "cannot locate repository root from " + Path.of("").toAbsolutePath());
  }

  private static String pythonCode(String source) {
    StringBuilder result = new StringBuilder(source.length());
    PythonLexicalState state = PythonLexicalState.CODE;
    for (int index = 0; index < source.length(); index++) {
      char current = source.charAt(index);
      char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
      char third = index + 2 < source.length() ? source.charAt(index + 2) : '\0';
      switch (state) {
        case CODE -> {
          if (current == '#') {
            result.append(' ');
            state = PythonLexicalState.LINE_COMMENT;
          } else if (current == '\'' && next == '\'' && third == '\'') {
            result.append("   ");
            index += 2;
            state = PythonLexicalState.SINGLE_TRIPLE_STRING;
          } else if (current == '"' && next == '"' && third == '"') {
            result.append("   ");
            index += 2;
            state = PythonLexicalState.DOUBLE_TRIPLE_STRING;
          } else {
            result.append(current);
            if (current == '\'') {
              state = PythonLexicalState.SINGLE_STRING;
            } else if (current == '"') {
              state = PythonLexicalState.DOUBLE_STRING;
            }
          }
        }
        case LINE_COMMENT -> {
          if (current == '\n') {
            result.append('\n');
            state = PythonLexicalState.CODE;
          } else {
            result.append(' ');
          }
        }
        case SINGLE_STRING -> {
          result.append(current);
          if (current == '\\' && next != '\0') {
            result.append(next);
            index++;
          } else if (current == '\'') {
            state = PythonLexicalState.CODE;
          }
        }
        case DOUBLE_STRING -> {
          result.append(current);
          if (current == '\\' && next != '\0') {
            result.append(next);
            index++;
          } else if (current == '"') {
            state = PythonLexicalState.CODE;
          }
        }
        case SINGLE_TRIPLE_STRING -> {
          if (current == '\'' && next == '\'' && third == '\'') {
            result.append("   ");
            index += 2;
            state = PythonLexicalState.CODE;
          } else {
            result.append(current == '\n' ? '\n' : ' ');
          }
        }
        case DOUBLE_TRIPLE_STRING -> {
          if (current == '"' && next == '"' && third == '"') {
            result.append("   ");
            index += 2;
            state = PythonLexicalState.CODE;
          } else {
            result.append(current == '\n' ? '\n' : ' ');
          }
        }
      }
    }
    return result.toString();
  }

  private static String javaStructure(String code) {
    StringBuilder result = new StringBuilder(code.length());
    JavaLexicalState state = JavaLexicalState.CODE;
    for (int index = 0; index < code.length(); index++) {
      char current = code.charAt(index);
      char next = index + 1 < code.length() ? code.charAt(index + 1) : '\0';
      char third = index + 2 < code.length() ? code.charAt(index + 2) : '\0';
      switch (state) {
        case CODE -> {
          if (current == '"' && next == '"' && third == '"') {
            result.append("   ");
            index += 2;
            state = JavaLexicalState.TEXT_BLOCK;
          } else if (current == '"') {
            result.append(' ');
            state = JavaLexicalState.STRING;
          } else if (current == '\'') {
            result.append(' ');
            state = JavaLexicalState.CHARACTER;
          } else {
            result.append(current);
          }
        }
        case STRING -> {
          if (current == '\\' && next != '\0') {
            result.append("  ");
            index++;
          } else {
            result.append(current == '\n' ? '\n' : ' ');
            if (current == '"') {
              state = JavaLexicalState.CODE;
            }
          }
        }
        case CHARACTER -> {
          if (current == '\\' && next != '\0') {
            result.append("  ");
            index++;
          } else {
            result.append(current == '\n' ? '\n' : ' ');
            if (current == '\'') {
              state = JavaLexicalState.CODE;
            }
          }
        }
        case TEXT_BLOCK -> {
          if (current == '"' && next == '"' && third == '"') {
            result.append("   ");
            index += 2;
            state = JavaLexicalState.CODE;
          } else {
            result.append(current == '\n' ? '\n' : ' ');
          }
        }
        case LINE_COMMENT, BLOCK_COMMENT ->
            throw new IllegalStateException("comments must be removed before masking literals");
      }
    }
    return result.toString();
  }

  private static String javaCode(String source) {
    StringBuilder result = new StringBuilder(source.length());
    JavaLexicalState state = JavaLexicalState.CODE;
    for (int index = 0; index < source.length(); index++) {
      char current = source.charAt(index);
      char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
      char third = index + 2 < source.length() ? source.charAt(index + 2) : '\0';
      switch (state) {
        case CODE -> {
          if (current == '/' && next == '/') {
            result.append("  ");
            index++;
            state = JavaLexicalState.LINE_COMMENT;
          } else if (current == '/' && next == '*') {
            result.append("  ");
            index++;
            state = JavaLexicalState.BLOCK_COMMENT;
          } else if (current == '"' && next == '"' && third == '"') {
            result.append("\"\"\"");
            index += 2;
            state = JavaLexicalState.TEXT_BLOCK;
          } else {
            result.append(current);
            if (current == '"') {
              state = JavaLexicalState.STRING;
            } else if (current == '\'') {
              state = JavaLexicalState.CHARACTER;
            }
          }
        }
        case LINE_COMMENT -> {
          if (current == '\n') {
            result.append('\n');
            state = JavaLexicalState.CODE;
          } else {
            result.append(' ');
          }
        }
        case BLOCK_COMMENT -> {
          if (current == '*' && next == '/') {
            result.append("  ");
            index++;
            state = JavaLexicalState.CODE;
          } else {
            result.append(current == '\n' ? '\n' : ' ');
          }
        }
        case STRING -> {
          result.append(current);
          if (current == '\\' && next != '\0') {
            result.append(next);
            index++;
          } else if (current == '"') {
            state = JavaLexicalState.CODE;
          }
        }
        case CHARACTER -> {
          result.append(current);
          if (current == '\\' && next != '\0') {
            result.append(next);
            index++;
          } else if (current == '\'') {
            state = JavaLexicalState.CODE;
          }
        }
        case TEXT_BLOCK -> {
          if (current == '"' && next == '"' && third == '"') {
            result.append("\"\"\"");
            index += 2;
            state = JavaLexicalState.CODE;
          } else {
            result.append(current);
          }
        }
      }
    }
    return result.toString();
  }

  private enum JavaLexicalState {
    CODE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    STRING,
    CHARACTER,
    TEXT_BLOCK
  }

  private enum PythonLexicalState {
    CODE,
    LINE_COMMENT,
    SINGLE_STRING,
    DOUBLE_STRING,
    SINGLE_TRIPLE_STRING,
    DOUBLE_TRIPLE_STRING
  }

  private record MutationFixture(String description, String source) {}

  private record SourceSpan(int start, int end) {}

  private record TemporalCapabilityAllowance(
      String source, String description, Pattern allowedForm, int expectedCount) {}

  private record ForbiddenPattern(String description, Pattern pattern) {}
}
