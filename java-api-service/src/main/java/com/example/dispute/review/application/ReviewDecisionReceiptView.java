package com.example.dispute.review.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable authority receipt minted only from the review decision transaction. */
public final class ReviewDecisionReceiptView {

    private final String schemaVersion;
    private final String receiptId;
    private final String factType;
    private final String taskId;
    private final String caseId;
    private final String packetId;
    private final int packetVersion;
    private final String packetContentHash;
    private final String decision;
    private final String reviewerId;
    private final String policyVersion;
    private final String requestHash;
    private final String frozenActionHash;
    private final String approvedActionHash;
    private final long outcomeEpoch;
    private final long fencingToken;
    private final long processRevision;
    private final boolean operationEligible;
    private final boolean operationRequestEmitted;
    private final OffsetDateTime recordedAt;
    private final String authoritySeal;

    private ReviewDecisionReceiptView(
            String schemaVersion,
            String receiptId,
            String factType,
            String taskId,
            String caseId,
            String packetId,
            int packetVersion,
            String packetContentHash,
            String decision,
            String reviewerId,
            String policyVersion,
            String requestHash,
            String frozenActionHash,
            String approvedActionHash,
            long outcomeEpoch,
            long fencingToken,
            long processRevision,
            boolean operationEligible,
            boolean operationRequestEmitted,
            OffsetDateTime recordedAt,
            String authoritySeal) {
        this.schemaVersion=schemaVersion;
        this.receiptId=receiptId;
        this.factType=factType;
        this.taskId=taskId;
        this.caseId=caseId;
        this.packetId=packetId;
        this.packetVersion=packetVersion;
        this.packetContentHash=packetContentHash;
        this.decision=decision;
        this.reviewerId=reviewerId;
        this.policyVersion=policyVersion;
        this.requestHash=requestHash;
        this.frozenActionHash=frozenActionHash;
        this.approvedActionHash=approvedActionHash;
        this.outcomeEpoch=outcomeEpoch;
        this.fencingToken=fencingToken;
        this.processRevision=processRevision;
        this.operationEligible=operationEligible;
        this.operationRequestEmitted=operationRequestEmitted;
        this.recordedAt=recordedAt;
        this.authoritySeal=authoritySeal;
    }

    static ReviewDecisionReceiptView mint(
            String schemaVersion,
            String receiptId,
            String factType,
            String taskId,
            String caseId,
            String packetId,
            int packetVersion,
            String packetContentHash,
            String decision,
            String reviewerId,
            String policyVersion,
            String requestHash,
            String frozenActionHash,
            String approvedActionHash,
            long outcomeEpoch,
            long fencingToken,
            long processRevision,
            boolean operationEligible,
            boolean operationRequestEmitted,
            OffsetDateTime recordedAt) {
        String seal=seal(
                schemaVersion,receiptId,factType,taskId,caseId,packetId,packetVersion,
                packetContentHash,decision,reviewerId,policyVersion,requestHash,frozenActionHash,
                approvedActionHash,outcomeEpoch,fencingToken,processRevision,operationEligible,
                operationRequestEmitted,recordedAt);
        return new ReviewDecisionReceiptView(
                schemaVersion,receiptId,factType,taskId,caseId,packetId,packetVersion,
                packetContentHash,decision,reviewerId,policyVersion,requestHash,frozenActionHash,
                approvedActionHash,outcomeEpoch,fencingToken,processRevision,operationEligible,
                operationRequestEmitted,recordedAt,seal);
    }

    boolean hasValidAuthoritySeal() {
        if(authoritySeal==null) return false;
        byte[] actual=authoritySeal.getBytes(StandardCharsets.US_ASCII);
        byte[] expected=seal(
                schemaVersion,receiptId,factType,taskId,caseId,packetId,packetVersion,
                packetContentHash,decision,reviewerId,policyVersion,requestHash,frozenActionHash,
                approvedActionHash,outcomeEpoch,fencingToken,processRevision,operationEligible,
                operationRequestEmitted,recordedAt).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual,expected);
    }

    public String schemaVersion() {return schemaVersion;}
    public String receiptId() {return receiptId;}
    public String factType() {return factType;}
    public String taskId() {return taskId;}
    public String caseId() {return caseId;}
    public String packetId() {return packetId;}
    public int packetVersion() {return packetVersion;}
    public String packetContentHash() {return packetContentHash;}
    public String decision() {return decision;}
    public String reviewerId() {return reviewerId;}
    public String policyVersion() {return policyVersion;}
    public String requestHash() {return requestHash;}
    public String frozenActionHash() {return frozenActionHash;}
    public String approvedActionHash() {return approvedActionHash;}
    public long outcomeEpoch() {return outcomeEpoch;}
    public long fencingToken() {return fencingToken;}
    public long processRevision() {return processRevision;}
    public boolean operationEligible() {return operationEligible;}
    public boolean operationRequestEmitted() {return operationRequestEmitted;}
    public OffsetDateTime recordedAt() {return recordedAt;}

    @Override
    public boolean equals(Object candidate) {
        if(this==candidate) return true;
        if(!(candidate instanceof ReviewDecisionReceiptView other)) return false;
        return packetVersion==other.packetVersion
                && outcomeEpoch==other.outcomeEpoch
                && fencingToken==other.fencingToken
                && processRevision==other.processRevision
                && operationEligible==other.operationEligible
                && operationRequestEmitted==other.operationRequestEmitted
                && Objects.equals(schemaVersion,other.schemaVersion)
                && Objects.equals(receiptId,other.receiptId)
                && Objects.equals(factType,other.factType)
                && Objects.equals(taskId,other.taskId)
                && Objects.equals(caseId,other.caseId)
                && Objects.equals(packetId,other.packetId)
                && Objects.equals(packetContentHash,other.packetContentHash)
                && Objects.equals(decision,other.decision)
                && Objects.equals(reviewerId,other.reviewerId)
                && Objects.equals(policyVersion,other.policyVersion)
                && Objects.equals(requestHash,other.requestHash)
                && Objects.equals(frozenActionHash,other.frozenActionHash)
                && Objects.equals(approvedActionHash,other.approvedActionHash)
                && Objects.equals(recordedAt,other.recordedAt)
                && Objects.equals(authoritySeal,other.authoritySeal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                schemaVersion,receiptId,factType,taskId,caseId,packetId,packetVersion,
                packetContentHash,decision,reviewerId,policyVersion,requestHash,frozenActionHash,
                approvedActionHash,outcomeEpoch,fencingToken,processRevision,operationEligible,
                operationRequestEmitted,recordedAt,authoritySeal);
    }

    @Override
    public String toString() {
        return "ReviewDecisionReceiptView[receiptId="+receiptId+", taskId="+taskId
                +", decision="+decision+", recordedAt="+recordedAt+"]";
    }

    private static String seal(Object... values) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(Object value:values) {
                byte[] bytes=canonicalValue(value).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(Exception exception) {
            throw new IllegalStateException("cannot seal review decision authority",exception);
        }
    }

    private static String canonicalValue(Object value) {
        if(value==null) return "<null>";
        if(value instanceof OffsetDateTime time) return time.toInstant().toString();
        return String.valueOf(value);
    }
}
