package com.example.dispute.agentstream.application;

import com.example.dispute.agentstream.application.AgentExecutionManifestStore.ManifestCommit;
import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitReceipt;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically commits a room fact and the immutable execution manifest. */
@Service
public class AgentRunFormalResultCommitter {

    private final AgentRunLedger ledger;
    private final AgentRunDomainResultCommitterRegistry domainCommitters;
    private final AgentExecutionManifestStore manifestStore;
    private final ObjectMapper objectMapper;

    public AgentRunFormalResultCommitter(
            AgentRunLedger ledger,
            AgentRunDomainResultCommitterRegistry domainCommitters,
            AgentExecutionManifestStore manifestStore,
            ObjectMapper objectMapper) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.domainCommitters = Objects.requireNonNull(domainCommitters, "domainCommitters");
        this.manifestStore = Objects.requireNonNull(manifestStore, "manifestStore");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Transactional
    public AgentRunFinalizationReceipt commit(FormalResultCommit command) {
        Objects.requireNonNull(command, "command");
        var committed = ledger.committedReceipt(command.request().agentRunId());
        validateInput(command);
        validateManifestHash(command.manifestCommit());
        if (committed.isPresent()) {
            validateReceipt(committed.orElseThrow(), command);
            return replay(committed.orElseThrow());
        }

        AgentRunDomainResultCommitter domainCommitter =
                domainCommitters.require(command.request());
        CommitReceipt domainReceipt = domainCommitter.commit(
                new CommitCommand(
                        command.request(),
                        command.result(),
                        command.manifestCommit().manifest()));
        validateDomainReceipt(domainReceipt, command);

        AgentRunFinalizationReceipt receipt = manifestStore.append(command.manifestCommit());
        validateReceipt(receipt, command);
        return receipt;
    }

    private void validateManifestHash(ManifestCommit manifestCommit) {
        String calculated =
                ContractJson.sha256Hex(objectMapper.valueToTree(manifestCommit.manifest()));
        if (!calculated.equals(manifestCommit.manifestHash())) {
            throw new IllegalArgumentException("manifestHash does not match the canonical manifest");
        }
    }

    private static void validateInput(FormalResultCommit command) {
        ExecuteAgentRunRequest request = command.request();
        ExecuteAgentRunResult result = command.result();
        ManifestCommit manifestCommit = command.manifestCommit();
        AgentExecutionManifest manifest = manifestCommit.manifest();
        RoomGraphCommand graphCommand = request.command();
        RoomGraphResult graphResult = result.graphResult();

        if (result.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED
                || graphResult == null
                || !request.agentRunId().equals(result.agentRunId())
                || !request.logicalRunId().equals(result.logicalRunId())
                || !request.attemptId().equals(result.attemptId())
                || request.attemptNo() != result.attemptNo()
                || !graphCommand.commandId().equals(graphResult.commandId())
                || !graphCommand.graphKey().equals(graphResult.graphKey())
                || !graphCommand.graphVersion().equals(graphResult.graphVersion())
                || !result.resultHash().equals(graphResult.outputHash())) {
            throw new IllegalArgumentException("formal result does not match its execution request");
        }
        if (!graphCommand.tenantSurrogate().equals(manifest.tenantSurrogate())
                || !graphCommand.caseId().equals(manifest.caseId())
                || graphCommand.roomEpoch() != manifest.roomEpoch()
                || graphCommand.processRevision() != manifest.processRevision()
                || !request.logicalRunId().equals(manifest.agentRun().logicalRunId())
                || !request.attemptId().equals(manifest.agentRun().attemptId())
                || !graphCommand.graphKey().equals(manifest.graph().graphKey())
                || !graphCommand.graphVersion().equals(manifest.graph().graphVersion())
                || !graphCommand.checkpointSchemaVersion()
                        .equals(manifest.graph().checkpointSchemaVersion())
                || !graphResult.checkpointId().equals(manifest.graph().checkpointId())
                || !graphCommand.requestHash().equals(manifest.model().requestHash())
                || !result.resultHash().equals(manifest.model().responseHash())
                || !result.resultHash().equals(manifest.output().sha256())
                || !graphResult.executionMetadata().promptVersion()
                        .equals(manifest.model().promptVersion())
                || !graphResult.executionMetadata().modelProfileId()
                        .equals(manifest.model().modelProfileId())
                || !graphResult.executionMetadata().policyVersion()
                        .equals(manifest.policyVersion())
                || !graphResult.executionMetadata().guardrailVersion()
                        .equals(manifest.guardrailVersion())
                || graphResult.usage().inputTokens() != manifest.usage().inputTokens()
                || graphResult.usage().outputTokens() != manifest.usage().outputTokens()
                || graphResult.usage().totalTokens() != manifest.usage().totalTokens()
                || manifestCommit.roomType() != graphCommand.roomType()
                || !result.resultHash().equals(manifestCommit.finalResultHash())
                || result.lastSequenceNo() != manifestCommit.finalStreamSequenceNo()) {
            throw new IllegalArgumentException("execution manifest does not match the formal result");
        }
    }

    private static void validateDomainReceipt(
            CommitReceipt receipt, FormalResultCommit command) {
        if (receipt == null) {
            throw new IllegalStateException("domain committer returned no receipt");
        }
        RoomGraphCommand graphCommand = command.request().command();
        AgentExecutionManifest manifest = command.manifestCommit().manifest();
        if (!graphCommand.caseId().equals(receipt.caseId())
                || graphCommand.roomEpoch() != receipt.roomEpoch()
                || graphCommand.processRevision() != receipt.processRevision()
                || !graphCommand.stageCode().equals(receipt.stageCode())
                || graphCommand.stageSequence() != receipt.stageSequence()
                || !graphCommand.actorScope().actorId().equals(receipt.actorId())
                || graphCommand.actorScope().actorRole() != receipt.actorRole()
                || graphCommand.actorScope().audience() != receipt.audience()
                || manifest.fencingToken() != receipt.fencingToken()
                || !command.result().resultHash().equals(receipt.resultHash())) {
            throw new IllegalStateException("domain commit receipt is outside the authorized fence");
        }
    }

    private static void validateReceipt(
            AgentRunFinalizationReceipt receipt, FormalResultCommit command) {
        AgentExecutionManifest manifest = command.manifestCommit().manifest();
        if (!command.request().agentRunId().equals(receipt.agentRunId())
                || !command.request().logicalRunId().equals(receipt.logicalRunId())
                || !command.request().attemptId().equals(receipt.attemptId())
                || command.request().attemptNo() != receipt.attemptNo()
                || manifest.fencingToken() != receipt.fencingToken()
                || !command.result().resultHash().equals(receipt.finalResultHash())
                || !manifest.manifestId().equals(receipt.manifestId())
                || !command.manifestCommit().manifestHash().equals(receipt.manifestHash())
                || command.result().lastSequenceNo() != receipt.finalStreamSequenceNo()) {
            throw new IllegalStateException("finalization receipt conflicts with the requested commit");
        }
    }

    private static AgentRunFinalizationReceipt replay(AgentRunFinalizationReceipt receipt) {
        return new AgentRunFinalizationReceipt(
                AgentRunFinalizationReceipt.SCHEMA_VERSION,
                receipt.agentRunId(),
                receipt.logicalRunId(),
                receipt.attemptId(),
                receipt.attemptNo(),
                receipt.fencingToken(),
                receipt.finalResultHash(),
                receipt.manifestId(),
                receipt.manifestHash(),
                receipt.finalStreamSequenceNo(),
                CommitStatus.ALREADY_COMMITTED,
                receipt.committedAt());
    }

    public record FormalResultCommit(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            ManifestCommit manifestCommit) {
        public FormalResultCommit {
            if (request == null || result == null || manifestCommit == null) {
                throw new IllegalArgumentException("formal result commit fields are required");
            }
        }
    }
}
