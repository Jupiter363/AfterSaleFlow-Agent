package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.GraphReconciliationException;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import java.util.Objects;

/** Target-only result reconciliation that re-seals the exact durable AgentRun command. */
public final class TargetE2EAgentGraphReconciliationClient
    implements AgentGraphReconciliationClient {

  private final String activationId;
  private final TargetE2EAgentRunIdentityResolver identityResolver;
  private final TargetE2EGraphEnvelopeCodec codec;
  private final TargetE2EGraphEnvelopeSigner signer;
  private final HttpTargetE2EGraphReconciliationClient client;
  private final GraphRegistryBindingPolicy registryBindingPolicy;

  public TargetE2EAgentGraphReconciliationClient(
      String activationId,
      TargetE2EAgentRunIdentityResolver identityResolver,
      TargetE2EGraphEnvelopeCodec codec,
      TargetE2EGraphEnvelopeSigner signer,
      HttpTargetE2EGraphReconciliationClient client,
      GraphRegistryBindingPolicy registryBindingPolicy) {
    TargetE2EGraphCommandEnvelope.requirePattern(
        activationId, TargetE2EGraphCommandEnvelope.ACTIVATION_ID, "activationId");
    this.activationId = activationId;
    this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.client = Objects.requireNonNull(client, "client");
    this.registryBindingPolicy =
        Objects.requireNonNull(registryBindingPolicy, "registryBindingPolicy");
  }

  @Override
  public GraphReconcileResponse reconcile(
      ExecuteAgentRunRequest request, AgentRunCancellationToken cancellationToken) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(cancellationToken, "cancellationToken").throwIfCancellationRequested();
    try {
      long roomFencingToken =
          Objects.requireNonNull(
                  identityResolver.resolve(request),
                  "durable AgentRun identity resolver returned no identity")
              .requireExact(request);
      RoomGraphCommand command = request.command();
      GraphRegistryBindingPolicy.ExpectedBinding binding =
          GraphRegistryBindingPolicy.requireExpected(
              registryBindingPolicy, GraphStreamVisibilityPolicy.Binding.from(command));
      TargetE2ESealedGraphCommand sealed =
          codec.sealCommand(activationId, roomFencingToken, command, binding, signer);
      HttpTargetE2EGraphReconciliationClient.ReconciledResult reconciled =
          client.reconcileAvailable(sealed, cancellationToken);
      RoomGraphResult result = reconciled.envelope().result();
      return new GraphReconcileResponse(
          "graph-reconcile-response.v1",
          GraphReconcileResponse.Disposition.RECONCILED_TERMINAL,
          command.threadId(),
          command.commandId(),
          command.requestHash(),
          request.logicalRunId(),
          request.attemptId(),
          command.graphKey(),
          command.graphVersion(),
          command.checkpointSchemaVersion(),
          "",
          result.checkpointId(),
          reconciled.resultRef(),
          result.outputHash(),
          binding.registryBindingHash(),
          binding.toolPolicyVersion(),
          result);
    } catch (TargetE2EGraphClientException failure) {
      cancellationToken.throwIfCancellationRequested();
      boolean retryable =
          failure.recoveryAction()
              != TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN;
      throw new GraphReconciliationException(
          failure.errorCode(),
          0,
          retryable,
          retryable
              ? AgentRunRecoveryAction.RETRY_SAME_COMMAND
              : AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
          "target Graph result reconciliation failed",
          failure);
    } catch (GraphReconciliationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      cancellationToken.throwIfCancellationRequested();
      throw GraphReconciliationException.protocol(
          "target Graph reconciliation binding is invalid", failure);
    }
  }
}
