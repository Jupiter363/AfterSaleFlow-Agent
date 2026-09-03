package com.example.dispute.review;

import com.example.dispute.review.application.ReviewDecisionReceiptView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;

final class ReviewDecisionReceiptTestFixture {

    private ReviewDecisionReceiptTestFixture() {}

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
        try {
            Method mint=ReviewDecisionReceiptView.class.getDeclaredMethod(
                    "mint",String.class,String.class,String.class,String.class,String.class,
                    String.class,int.class,String.class,String.class,String.class,String.class,
                    String.class,String.class,String.class,long.class,long.class,long.class,
                    boolean.class,boolean.class,OffsetDateTime.class);
            mint.setAccessible(true);
            return (ReviewDecisionReceiptView)mint.invoke(
                    null,schemaVersion,receiptId,factType,taskId,caseId,packetId,packetVersion,
                    packetContentHash,decision,reviewerId,policyVersion,requestHash,
                    frozenActionHash,approvedActionHash,outcomeEpoch,fencingToken,
                    processRevision,operationEligible,operationRequestEmitted,recordedAt);
        } catch(ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static ReviewDecisionReceiptView substitute(
            ReviewDecisionReceiptView source,String fieldName) {
        try {
            Field sealField=ReviewDecisionReceiptView.class.getDeclaredField("authoritySeal");
            sealField.setAccessible(true);
            String authoritySeal=(String)sealField.get(source);
            Constructor<ReviewDecisionReceiptView> constructor=
                    ReviewDecisionReceiptView.class.getDeclaredConstructor(
                            String.class,String.class,String.class,String.class,String.class,
                            String.class,int.class,String.class,String.class,String.class,String.class,
                            String.class,String.class,String.class,long.class,long.class,long.class,
                            boolean.class,boolean.class,OffsetDateTime.class,String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    "schemaVersion".equals(fieldName)
                            ?"review-decision-receipt.v2":source.schemaVersion(),
                    "receiptId".equals(fieldName)?"RECEIPT_2":source.receiptId(),
                    "factType".equals(fieldName)?"SYSTEM_SLA_ESCALATION":source.factType(),
                    "taskId".equals(fieldName)?"TASK_2":source.taskId(),
                    "caseId".equals(fieldName)?"CASE_2":source.caseId(),
                    "packetId".equals(fieldName)?"PACKET_2":source.packetId(),
                    "packetVersion".equals(fieldName)?source.packetVersion()+1:source.packetVersion(),
                    "packetContentHash".equals(fieldName)?"9".repeat(64):source.packetContentHash(),
                    "decision".equals(fieldName)?"MODIFY_AND_APPROVE":source.decision(),
                    "reviewerId".equals(fieldName)?"reviewer-substituted":source.reviewerId(),
                    "policyVersion".equals(fieldName)?"policy-v2":source.policyVersion(),
                    "requestHash".equals(fieldName)?"9".repeat(64):source.requestHash(),
                    "frozenActionHash".equals(fieldName)?"9".repeat(64):source.frozenActionHash(),
                    "approvedActionHash".equals(fieldName)?"9".repeat(64):source.approvedActionHash(),
                    "outcomeEpoch".equals(fieldName)?source.outcomeEpoch()+1:source.outcomeEpoch(),
                    "fencingToken".equals(fieldName)?source.fencingToken()+1:source.fencingToken(),
                    "processRevision".equals(fieldName)
                            ?source.processRevision()+1:source.processRevision(),
                    "operationEligible".equals(fieldName)
                            ?!source.operationEligible():source.operationEligible(),
                    "operationRequestEmitted".equals(fieldName)
                            ?!source.operationRequestEmitted():source.operationRequestEmitted(),
                    "recordedAt".equals(fieldName)?source.recordedAt().plusSeconds(1):source.recordedAt(),
                    authoritySeal);
        } catch(ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String reviewerAuthorityHash(String reviewerId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("reviewer-authority:v1:"+reviewerId)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch(Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
