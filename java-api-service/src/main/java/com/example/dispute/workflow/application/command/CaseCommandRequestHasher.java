package com.example.dispute.workflow.application.command;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;

public final class CaseCommandRequestHasher {

    private CaseCommandRequestHasher() {}

    public static String hash(
            String tenantSurrogate,
            String caseId,
            String commandId,
            AcceptCaseCommand command,
            ActorRef actor) {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("schema_version", "case-command-request.v1");
        root.put("tenant_surrogate", tenantSurrogate);
        root.put("case_id", caseId);
        root.put("command_id", commandId);
        root.put("command_type", command.commandType().name());
        root.put("room_type", command.roomType().name());
        root.put("room_epoch", command.roomEpoch());

        var actorNode = root.putObject("actor_ref");
        actorNode.put("actor_id", actor.actorId());
        actorNode.put("actor_role", actor.actorRole().name());
        var scopesNode = actorNode.putArray("actor_scopes");
        var scopes = new ArrayList<>(actor.actorScopes());
        scopes.sort(String::compareTo);
        scopes.forEach(scopesNode::add);

        var payloadNode = root.putObject("payload_ref");
        payloadNode.put("schema_version", command.payloadRef().schemaVersion());
        payloadNode.put("uri", command.payloadRef().uri());
        payloadNode.put("sha256", command.payloadRef().sha256());
        payloadNode.put("size_bytes", command.payloadRef().sizeBytes());
        root.put("expected_process_revision", command.expectedProcessRevision());
        root.put("deadline_at", command.deadlineAt().toString());
        return ContractJson.sha256Hex(root);
    }
}
