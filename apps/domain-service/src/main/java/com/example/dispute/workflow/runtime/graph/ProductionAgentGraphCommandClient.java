package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Production-only AgentRun adapter; it has no SHADOW or legacy transport fallback. */
public final class ProductionAgentGraphCommandClient implements AgentGraphCommandClient {

  private final String activationId;
  private final ProductionAgentRunIdentityResolver identityResolver;
  private final ProductionGraphEnvelopeCodec codec;
  private final ProductionGraphEnvelopeSigner signer;
  private final ProductionGraphProposalClient proposalClient;
  private final GraphStreamVisibilityPolicy visibilityPolicy;
  private final GraphRegistryBindingPolicy registryBindingPolicy;

  public ProductionAgentGraphCommandClient(
      String activationId,
      ProductionAgentRunIdentityResolver identityResolver,
      ProductionGraphEnvelopeCodec codec,
      ProductionGraphEnvelopeSigner signer,
      ProductionGraphProposalClient proposalClient,
      GraphStreamVisibilityPolicy visibilityPolicy,
      GraphRegistryBindingPolicy registryBindingPolicy) {
    ProductionGraphCommandEnvelope.requirePattern(
        activationId, ProductionGraphCommandEnvelope.ACTIVATION_ID, "activationId");
    this.activationId = activationId;
    this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.proposalClient = Objects.requireNonNull(proposalClient, "proposalClient");
    this.visibilityPolicy = Objects.requireNonNull(visibilityPolicy, "visibilityPolicy");
    this.registryBindingPolicy =
        Objects.requireNonNull(registryBindingPolicy, "registryBindingPolicy");
  }

  @Override
  public RoomGraphResult execute(
      ExecuteAgentRunRequest request,
      ExecutionMode mode,
      Consumer<AgentStreamEvent> eventSink,
      AgentRunCancellationToken cancellationToken) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(eventSink, "eventSink");
    Objects.requireNonNull(cancellationToken, "cancellationToken").throwIfCancellationRequested();
    if (mode != ExecutionMode.EXECUTE_OR_RECONCILE) {
      throw AgentRunExecutionException.failLogicalRun(
          "PRODUCTION_RUNTIME_GRAPH_RECONCILE_ONLY_UNSUPPORTED",
          "target Graph reconciliation requires the original sealed command",
          request.publicSequenceOffset(),
          false,
          null);
    }

    EventProgress progress = new EventProgress(request.publicSequenceOffset());
    try {
      ProductionAgentRunIdentityResolver.DurableIdentity identity =
          Objects.requireNonNull(
              identityResolver.resolve(request),
              "durable AgentRun identity resolver returned no identity");
      long roomFencingToken = identity.requireExact(request);
      RoomGraphCommand command = request.command();
      GraphStreamVisibilityPolicy.Binding binding =
          GraphStreamVisibilityPolicy.Binding.from(command);
      Map<String, Set<String>> configuredVisibleFields =
          GraphStreamVisibilityPolicy.immutablePolicy(
              Objects.requireNonNull(
                  visibilityPolicy.allowedVisibleFields(binding),
                  "target Graph visibility binding is not registered"));
      GraphRegistryBindingPolicy.ExpectedBinding registryBinding =
          GraphRegistryBindingPolicy.requireExpected(registryBindingPolicy, binding);
      ProductionSealedGraphCommand sealed =
          codec.sealCommand(
              activationId, roomFencingToken, command, registryBinding, signer);
      Map<String, Set<String>> visibleFields =
          ProductionGraphStreamVisibility.requireExactPolicy(
              sealed.envelope().command().roomType(), configuredVisibleFields);
      ProductionGraphResultEnvelope result =
          proposalClient.execute(
              sealed,
              visibleFields,
              event -> {
                eventSink.accept(event);
                progress.accept(event);
              },
              cancellationToken);
      requireExactResult(result, sealed.envelope());
      return result.result();
    } catch (ProductionGraphClientException failure) {
      cancellationToken.throwIfCancellationRequested();
      throw executionFailure(failure, progress);
    } catch (AgentRunExecutionException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      cancellationToken.throwIfCancellationRequested();
      throw AgentRunExecutionException.failLogicalRun(
          "PRODUCTION_RUNTIME_GRAPH_BINDING_REJECTED",
          "target Graph request or durable identity binding is invalid",
          progress.lastSequenceNo,
          progress.publicOutputEmitted,
          failure);
    }
  }

  private static AgentRunExecutionException executionFailure(
      ProductionGraphClientException failure, EventProgress progress) {
    return switch (failure.recoveryAction()) {
      case RETRY_SAME_SEALED_COMMAND -> AgentRunExecutionException.retrySameCommand(
          failure.errorCode(),
          "target Graph requested replay of the same command",
          progress.lastSequenceNo,
          progress.publicOutputEmitted,
          failure);
      case CREATE_NEXT_ATTEMPT -> AgentRunExecutionException.createNextAttempt(
          failure.errorCode(),
          "target Graph durably aborted the current attempt",
          progress.lastSequenceNo,
          progress.publicOutputEmitted,
          failure);
      case RECONCILE_SEALED_COMMAND -> AgentRunExecutionException.reconcileTerminal(
          failure.errorCode(),
          "target Graph requires terminal reconciliation of the sealed command",
          progress.lastSequenceNo,
          progress.publicOutputEmitted,
          failure);
      case FAIL_LOGICAL_RUN -> AgentRunExecutionException.failLogicalRun(
          failure.errorCode(),
          "target Graph rejected the immutable command",
          progress.lastSequenceNo,
          progress.publicOutputEmitted,
          failure);
    };
  }

  private static void requireExactResult(
      ProductionGraphResultEnvelope envelope, ProductionGraphCommandEnvelope commandEnvelope) {
    Objects.requireNonNull(envelope, "target Graph returned no result envelope");
    RoomGraphCommand command = commandEnvelope.command();
    RoomGraphResult result = envelope.result();
    boolean exact =
        envelope.executionLane().equals(commandEnvelope.executionLane())
            && envelope.activationId().equals(commandEnvelope.activationId())
            && envelope.roomFencingToken() == commandEnvelope.roomFencingToken()
            && ProductionGraphEnvelopeCodec.constantTimeEquals(
                envelope.commandHash(), commandEnvelope.commandHash())
            && ProductionGraphEnvelopeCodec.constantTimeEquals(
                envelope.commandEnvelopeHash(), commandEnvelope.commandEnvelopeHash())
            && ProductionGraphEnvelopeCodec.constantTimeEquals(
                envelope.resultHash(), result.outputHash())
            && command.commandId().equals(result.commandId())
            && command.logicalRunId().equals(result.logicalRunId())
            && command.attemptId().equals(result.attemptId())
            && command.graphKey().equals(result.graphKey())
            && command.graphVersion().equals(result.graphVersion());
    if (!exact) {
      throw new IllegalArgumentException(
          "target Graph result differs from the sealed command or Java room fence");
    }
  }

  private static final class EventProgress {
    private long lastSequenceNo;
    private boolean publicOutputEmitted;

    private EventProgress(long baseline) {
      this.lastSequenceNo = baseline;
    }

    private void accept(AgentStreamEvent event) {
      AgentStreamEvent required = Objects.requireNonNull(event, "event");
      lastSequenceNo = Math.max(lastSequenceNo, required.sequenceNo());
      publicOutputEmitted |= required.eventType() == StreamEventType.VISIBLE_DELTA;
    }
  }
}
