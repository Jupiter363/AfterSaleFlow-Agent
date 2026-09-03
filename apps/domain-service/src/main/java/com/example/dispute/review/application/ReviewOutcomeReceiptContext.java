package com.example.dispute.review.application;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Server-minted refs and hashes supplied by the future Outcome finalizer. */
public record ReviewOutcomeReceiptContext(
        String workflowId,
        String receiptHash,
        String requestHash,
        String reviewerAuthorityRef,
        String actionSnapshotRef,
        String approvedActionSnapshotRef,
        String decisionRecordHash,
        String reasonRef,
        String reasonHash,
        String operationKeyHash,
        String requiredOperationSetRef,
        String requiredOperationSetHash,
        long requiredOperationCount,
        String idempotencyKeyHash,
        long sourceRevision,
        long committedEventSequence,
        boolean syntheticOnly,
        ReviewPacketAuthorizationView authorization) {

    private static final long MAX_SAFE_INTEGER=9_007_199_254_740_991L;
    private static final Pattern IDENTIFIER=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern OPAQUE_REF=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");
    private static final Pattern SHA_256=Pattern.compile("[0-9a-f]{64}");

    public ReviewOutcomeReceiptContext {
        requireIdentifier(workflowId,"workflowId");
        requireHash(receiptHash,"receiptHash");
        requireHash(requestHash,"requestHash");
        requireOpaqueRef(reviewerAuthorityRef,"reviewerAuthorityRef");
        if(!reviewerAuthorityRef.matches("reviewer-authority:[0-9a-f]{64}"))
            throw new IllegalArgumentException("reviewerAuthorityRef must bind a SHA-256 authority hash");
        requireOpaqueRef(actionSnapshotRef,"actionSnapshotRef");
        optionalOpaqueRef(approvedActionSnapshotRef,"approvedActionSnapshotRef");
        requireHash(decisionRecordHash,"decisionRecordHash");
        requireOpaqueRef(reasonRef,"reasonRef");
        requireHash(reasonHash,"reasonHash");
        optionalHash(operationKeyHash,"operationKeyHash");
        requireOpaqueRef(requiredOperationSetRef,"requiredOperationSetRef");
        requireHash(requiredOperationSetHash,"requiredOperationSetHash");
        requireCount(requiredOperationCount,"requiredOperationCount");
        requireHash(idempotencyKeyHash,"idempotencyKeyHash");
        requireCount(sourceRevision,"sourceRevision");
        requirePositiveCount(committedEventSequence,"committedEventSequence");
        if((approvedActionSnapshotRef==null)!=(operationKeyHash==null))
            throw new IllegalArgumentException(
                    "approvedActionSnapshotRef and operationKeyHash must have the same approval semantics");
        if(operationKeyHash==null&&requiredOperationCount!=0)
            throw new IllegalArgumentException("a nonexecution context requires an empty operation set");
        if(operationKeyHash!=null&&requiredOperationCount<1)
            throw new IllegalArgumentException("an execution context requires at least one operation");
        if(authorization==null)
            throw new IllegalArgumentException("server-side packet authorization is required");
        requireHash(authorization.actionHash(),"authorization.actionHash");
        if(sourceRevision==MAX_SAFE_INTEGER
                || authorization.processRevision()!=sourceRevision+1)
            throw new IllegalArgumentException(
                    "authorization revision must be exactly sourceRevision + 1");
    }

    /** Canonical request-hash preimage. The requestHash itself is the digest over this binding. */
    public Map<String,Object> canonicalRequestBinding() {
        Map<String,Object> binding=new TreeMap<>();
        binding.put("action_snapshot_ref",actionSnapshotRef);
        binding.put("approved_action_snapshot_ref",approvedActionSnapshotRef);
        binding.put("authorization",canonicalAuthorizationBinding());
        binding.put("committed_event_sequence",committedEventSequence);
        binding.put("decision_record_hash",decisionRecordHash);
        binding.put("idempotency_key_hash",idempotencyKeyHash);
        binding.put("operation_key_hash",operationKeyHash);
        binding.put("reason_hash",reasonHash);
        binding.put("reason_ref",reasonRef);
        binding.put("receipt_hash",receiptHash);
        binding.put("required_operation_count",requiredOperationCount);
        binding.put("required_operation_set_hash",requiredOperationSetHash);
        binding.put("required_operation_set_ref",requiredOperationSetRef);
        binding.put("reviewer_authority_ref",reviewerAuthorityRef);
        binding.put("source_revision",sourceRevision);
        binding.put("synthetic_only",syntheticOnly);
        binding.put("workflow_id",workflowId);
        return immutable(binding);
    }

    public ReviewOutcomeReceiptContext withRequestHash(String replacementRequestHash) {
        return new ReviewOutcomeReceiptContext(
                workflowId,receiptHash,replacementRequestHash,reviewerAuthorityRef,
                actionSnapshotRef,approvedActionSnapshotRef,decisionRecordHash,reasonRef,
                reasonHash,operationKeyHash,requiredOperationSetRef,requiredOperationSetHash,
                requiredOperationCount,idempotencyKeyHash,sourceRevision,committedEventSequence,
                syntheticOnly,authorization);
    }

    private Map<String,Object> canonicalAuthorizationBinding() {
        Map<String,Object> binding=new TreeMap<>();
        binding.put("action_hash",authorization.actionHash());
        binding.put("authorized_artifact_refs",immutable(new TreeMap<>(authorization.authorizedArtifactRefs())));
        binding.put("case_id",authorization.caseId());
        binding.put("deadline",authorization.deadline().toInstant().toString());
        binding.put("fencing_token",authorization.fencingToken());
        binding.put("packet_content_hash",authorization.packetContentHash());
        binding.put("packet_id",authorization.packetId());
        binding.put("packet_version",authorization.packetVersion());
        binding.put("policy_version",authorization.policyVersion());
        binding.put("process_revision",authorization.processRevision());
        binding.put("review_task_id",authorization.reviewTaskId());
        binding.put("review_opened_at",authorization.reviewOpenedAt().toInstant().toString());
        binding.put("reviewer_authority_hash",authorization.reviewerAuthorityHash());
        binding.put("room_epoch",authorization.roomEpoch());
        binding.put("schema_version",authorization.schemaVersion());
        binding.put("task_status",authorization.taskStatus());
        return immutable(binding);
    }

    private static <K,V> Map<K,V> immutable(Map<K,V> values) {
        return Collections.unmodifiableMap(values);
    }

    private static void optionalHash(String value,String name) {
        if(value!=null) requireHash(value,name);
    }

    private static void optionalOpaqueRef(String value,String name) {
        if(value!=null) requireOpaqueRef(value,name);
    }

    private static void requireHash(String value,String name) {
        if(value==null||!SHA_256.matcher(value).matches())
            throw new IllegalArgumentException(name+" must be a lowercase SHA-256");
    }

    private static void requireIdentifier(String value,String name) {
        if(value==null||!IDENTIFIER.matcher(value).matches())
            throw new IllegalArgumentException(name+" must be a bounded identifier");
    }

    private static void requireOpaqueRef(String value,String name) {
        if(value==null||value.contains("://")||!OPAQUE_REF.matcher(value).matches())
            throw new IllegalArgumentException(name+" must be a bounded opaque ref");
    }

    private static void requireCount(long value,String name) {
        if(value<0||value>MAX_SAFE_INTEGER)
            throw new IllegalArgumentException(name+" is outside the safe range");
    }

    private static void requirePositiveCount(long value,String name) {
        requireCount(value,name);
        if(value==0) throw new IllegalArgumentException(name+" must be positive");
    }
}
