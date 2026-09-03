package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.review.application.ReviewDecisionReceiptView;
import com.example.dispute.review.application.ReviewOutcomeProtocolAdapter;
import com.example.dispute.review.application.ReviewOutcomeReceiptContext;
import com.example.dispute.review.application.ReviewPacketAuthorizationView;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import java.lang.reflect.Modifier;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewOutcomeProtocolAdapterTest {

    @Test
  void mapsBoundHumanReceiptWithoutStartingOrExecutingAnything() {
        OffsetDateTime committedAt=OffsetDateTime.of(2026,7,24,9,0,0,0,ZoneOffset.UTC);
        ReviewDecisionReceiptView receipt=ReviewDecisionReceiptTestFixture.mint(
                "review-decision-receipt.v1","RECEIPT_1","HUMAN_DECISION","TASK_1","CASE_1",
                "PACKET_1",2,"a".repeat(64),"APPROVE","reviewer-local","policy-v1",
                "b".repeat(64),"c".repeat(64),"c".repeat(64),4,7,3,true,false,committedAt);
        ReviewOutcomeReceiptContext context=new ReviewOutcomeReceiptContext(
                "outcome:CASE_1:4","d".repeat(64),"b".repeat(64),
                "reviewer-authority:"+reviewerAuthorityHash(),
                "action-snapshot:original","action-snapshot:approved","e".repeat(64),
                "reason:RECEIPT_1","f".repeat(64),"1".repeat(64),"operations:RECEIPT_1",
                "2".repeat(64),1,"3".repeat(64),2,9,true,
                new ReviewPacketAuthorizationView(
                        "review-packet-authorization.v1","CASE_1","TASK_1",reviewerAuthorityHash(),
                         "PACKET_1",2,"a".repeat(64),"c".repeat(64),"PENDING","policy-v1",
                         committedAt.minusHours(1),committedAt.plusHours(1),4,3,7,
                         java.util.Map.of("packet","PACKET_1")));

        var wire=ReviewOutcomeProtocolAdapter.humanDecision(receipt,context);

        assertThat(wire.decision()).isEqualTo(OutcomeWireTypes.ReviewDecision.APPROVE);
        assertThat(wire.executionAuthorized()).isTrue();
        assertThat(wire.frozenReviewPacketHash()).isEqualTo(receipt.packetContentHash());
        assertThat(wire.requestHash()).isEqualTo(receipt.requestHash());
    }

    @Test
    void boundedDecisionCodeModificationRetainsTheFrozenExecutionActionHash() {
        OffsetDateTime committedAt=OffsetDateTime.of(2026,7,24,9,0,0,0,ZoneOffset.UTC);
        ReviewDecisionReceiptView receipt=ReviewDecisionReceiptTestFixture.mint(
                "review-decision-receipt.v1","RECEIPT_1","HUMAN_DECISION","TASK_1","CASE_1",
                "PACKET_1",2,"a".repeat(64),"MODIFY_AND_APPROVE","reviewer-local","policy-v1",
                "b".repeat(64),"c".repeat(64),"c".repeat(64),4,7,3,true,false,committedAt);
        ReviewPacketAuthorizationView authorization=new ReviewPacketAuthorizationView(
                "review-packet-authorization.v1","CASE_1","TASK_1",reviewerAuthorityHash(),
                "PACKET_1",2,"a".repeat(64),"c".repeat(64),"PENDING","policy-v1",
                committedAt.minusHours(1),committedAt.plusHours(1),4,3,7,
                Map.of("packet","PACKET_1",
                        "decision_contract","hearing-decision-action.v1"));

        var wire=ReviewOutcomeProtocolAdapter.humanDecision(
                receipt,context(authorization,"b".repeat(64),"1".repeat(64),1,2));

        assertThat(wire.decision())
                .isEqualTo(OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE);
        assertThat(wire.approvedActionSnapshotHash())
                .isEqualTo(wire.actionSnapshotHash());
    }

    @Test
    void reviewAuthorizationAcceptsTheFirstZeroEpochButRejectsNegativeCoordinates() {
        OffsetDateTime openedAt=OffsetDateTime.of(2026,7,24,8,0,0,0,ZoneOffset.UTC);
        ReviewPacketAuthorizationView first=new ReviewPacketAuthorizationView(
                "review-packet-authorization.v1","CASE_ZERO","TASK_ZERO",reviewerAuthorityHash(),
                "PACKET_ZERO",1,"a".repeat(64),"b".repeat(64),"PENDING","policy-v1",
                openedAt,openedAt.plusHours(1),0,0,1,Map.of("packet","PACKET_ZERO"));

        assertThat(first.roomEpoch()).isZero();
        assertThatThrownBy(() -> new ReviewPacketAuthorizationView(
                "review-packet-authorization.v1","CASE_NEGATIVE","TASK_NEGATIVE",
                reviewerAuthorityHash(),"PACKET_NEGATIVE",1,"a".repeat(64),"b".repeat(64),
                "PENDING","policy-v1",openedAt,openedAt.plusHours(1),-1,0,1,
                Map.of("packet","PACKET_NEGATIVE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch, revision, and fence");
    }

    @Test
    void receiptAuthorityHasNoPublicConstructionOrMintSurface() {
        assertThat(Modifier.isFinal(ReviewDecisionReceiptView.class.getModifiers())).isTrue();
        assertThat(Arrays.stream(ReviewDecisionReceiptView.class.getDeclaredFields()))
                .allMatch(field->Modifier.isPrivate(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers()));
        assertThat(Arrays.stream(ReviewDecisionReceiptView.class.getDeclaredConstructors()))
                .allMatch(constructor->Modifier.isPrivate(constructor.getModifiers()));
        java.lang.reflect.Method mint=Arrays.stream(ReviewDecisionReceiptView.class.getDeclaredMethods())
                .filter(method->method.getName().equals("mint"))
                .findFirst()
                .orElseThrow();
        assertThat(Modifier.isPublic(mint.getModifiers())).isFalse();
        assertThat(Modifier.isProtected(mint.getModifiers())).isFalse();
        assertThat(Modifier.isPrivate(mint.getModifiers())).isFalse();
        assertThat(mint.canAccess(null))
                .as("the test package must not have normal access to the package-private mint")
                .isFalse();
        assertThat(Arrays.stream(ReviewDecisionReceiptView.class.getDeclaredMethods())
                        .filter(method->Modifier.isPublic(method.getModifiers()))
                        .filter(method->Modifier.isStatic(method.getModifiers()))
                        .filter(method->method.getReturnType()==ReviewDecisionReceiptView.class))
                .isEmpty();
        assertThat(Arrays.stream(ReviewDecisionReceiptView.class.getMethods())
                        .map(java.lang.reflect.Method::getName))
                .noneMatch(name->name.startsWith("with")
                        || name.equals("copy")||name.equals("mint")||name.equals("create")
                        || name.equals("of"));
    }

    @ParameterizedTest
    @ValueSource(strings={
            "schemaVersion","receiptId","factType","taskId","caseId","packetId",
            "packetVersion","packetContentHash","decision","reviewerId","policyVersion",
            "requestHash","frozenActionHash","approvedActionHash","outcomeEpoch",
            "fencingToken","processRevision","operationEligible","operationRequestEmitted",
            "recordedAt"
    })
    void adapterRejectsEveryReceiptFieldSubstitution(String fieldName) {
        ReviewDecisionReceiptView receipt=ReviewDecisionReceiptTestFixture.mint(
                "review-decision-receipt.v1","RECEIPT_1","HUMAN_DECISION","TASK_1","CASE_1",
                "PACKET_1",2,"a".repeat(64),"APPROVE","reviewer-local","policy-v1",
                "b".repeat(64),"c".repeat(64),"c".repeat(64),4,7,3,true,false,
                OffsetDateTime.of(2026,7,24,9,0,0,0,ZoneOffset.UTC));
        ReviewDecisionReceiptView substituted=
                ReviewDecisionReceiptTestFixture.substitute(receipt,fieldName);

        assertThatThrownBy(() -> ReviewOutcomeProtocolAdapter.humanDecision(
                        substituted,context(authorization("c".repeat(64)),
                                "b".repeat(64),"1".repeat(64),1,2)))
                .as("receipt authority field %s",fieldName)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void canonicalBindingContainsEveryForwardedContextAndAuthorizationField() {
        ReviewOutcomeReceiptContext context=context(authorization("c".repeat(64)),
                "b".repeat(64),"1".repeat(64),1,2);

        assertThat(context.canonicalRequestBinding()).containsOnlyKeys(
                "action_snapshot_ref","approved_action_snapshot_ref","authorization",
                "committed_event_sequence","decision_record_hash","idempotency_key_hash",
                "operation_key_hash","reason_hash","reason_ref","receipt_hash",
                "required_operation_count","required_operation_set_hash",
                "required_operation_set_ref","reviewer_authority_ref","source_revision",
                "synthetic_only","workflow_id");
        Map<String,Object> authorizationBinding=(Map<String,Object>)
                context.canonicalRequestBinding().get("authorization");
        assertThat(authorizationBinding).containsOnlyKeys(
                        "action_hash","authorized_artifact_refs","case_id","deadline",
                        "fencing_token","packet_content_hash","packet_id","packet_version",
                        "policy_version","process_revision","review_opened_at","review_task_id",
                        "reviewer_authority_hash","room_epoch","schema_version","task_status");
        assertThatThrownBy(() -> context.canonicalRequestBinding().put("forged","value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void contextRejectsMalformedHashesCountsAndRevisionOrder() {
        ReviewPacketAuthorizationView authorization=authorization("c".repeat(64));

        assertThatThrownBy(() -> context(authorization,"not-a-hash","1".repeat(64),1,2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestHash");
        assertThatThrownBy(() -> context(authorization,"b".repeat(64),"1".repeat(64),-1,2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requiredOperationCount");
        assertThatThrownBy(() -> context(authorization,"b".repeat(64),"1".repeat(64),1,4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly sourceRevision + 1");
        assertThatThrownBy(() -> context(authorization,"b".repeat(64),"1".repeat(64),1,3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly sourceRevision + 1");
        assertThatThrownBy(() -> context(authorization,"b".repeat(64),"1".repeat(64),1,1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly sourceRevision + 1");
        assertThatThrownBy(() -> context(
                        authorization,"b".repeat(64),"1".repeat(64),1,2,0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("committedEventSequence must be positive");
        long maxSafeInteger=9_007_199_254_740_991L;
        assertThatThrownBy(() -> context(
                        authorization("c".repeat(64),maxSafeInteger),
                        "b".repeat(64),"1".repeat(64),1,maxSafeInteger,maxSafeInteger))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly sourceRevision + 1");
        assertThatThrownBy(() -> context(
                        authorization("not-a-hash"),"b".repeat(64),"1".repeat(64),1,2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authorization.actionHash");
    }

    @Test
    void preDeadlineCommitRemainsValidWhenDeliveryOccursAfterDeadline() {
        OffsetDateTime openedAt=OffsetDateTime.of(2026,7,24,8,0,0,0,ZoneOffset.UTC);
        OffsetDateTime deadline=openedAt.plusHours(2);
        OffsetDateTime committedAt=deadline.minusNanos(1);
        OffsetDateTime deliveredAt=deadline.plusHours(3);
        ReviewPacketAuthorizationView authorization=new ReviewPacketAuthorizationView(
                "review-packet-authorization.v1","CASE_1","TASK_1",reviewerAuthorityHash(),
                "PACKET_1",2,"a".repeat(64),"c".repeat(64),"PENDING","policy-v1",
                openedAt,deadline,4,3,7,Map.of("packet","PACKET_1"));
        ReviewDecisionReceiptView receipt=ReviewDecisionReceiptTestFixture.mint(
                "review-decision-receipt.v1","RECEIPT_1","HUMAN_DECISION","TASK_1","CASE_1",
                "PACKET_1",2,"a".repeat(64),"APPROVE","reviewer-local","policy-v1",
                "b".repeat(64),"c".repeat(64),"c".repeat(64),4,7,3,true,false,committedAt);

        var wire=ReviewOutcomeProtocolAdapter.humanDecision(
                receipt,context(authorization,"b".repeat(64),"1".repeat(64),1,2));

        assertThat(deliveredAt).isAfter(deadline);
        assertThat(wire.committedAt()).isEqualTo(committedAt.toInstant());
    }

    @Test
    void commitOutsideAuthoritativeReviewWindowIsRejected() {
        ReviewPacketAuthorizationView authorization=authorization("c".repeat(64));
        ReviewDecisionReceiptView receipt=ReviewDecisionReceiptTestFixture.mint(
                "review-decision-receipt.v1","RECEIPT_1","HUMAN_DECISION","TASK_1","CASE_1",
                "PACKET_1",2,"a".repeat(64),"APPROVE","reviewer-local","policy-v1",
                "b".repeat(64),"c".repeat(64),"c".repeat(64),4,7,3,true,false,
                authorization.deadline().plusNanos(1));

        assertThatThrownBy(() -> ReviewOutcomeProtocolAdapter.humanDecision(
                        receipt,context(authorization,"b".repeat(64),"1".repeat(64),1,2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative review window");
    }

    private static ReviewOutcomeReceiptContext context(
            ReviewPacketAuthorizationView authorization,
            String requestHash,
            String operationKeyHash,
            long operationCount,
            long sourceRevision) {
        return context(
                authorization,requestHash,operationKeyHash,operationCount,sourceRevision,9);
    }

    private static ReviewOutcomeReceiptContext context(
            ReviewPacketAuthorizationView authorization,
            String requestHash,
            String operationKeyHash,
            long operationCount,
            long sourceRevision,
            long committedEventSequence) {
        return new ReviewOutcomeReceiptContext(
                "outcome:CASE_1:4","d".repeat(64),requestHash,
                "reviewer-authority:"+reviewerAuthorityHash(),
                "action-snapshot:original","action-snapshot:approved","e".repeat(64),
                "reason:RECEIPT_1","f".repeat(64),operationKeyHash,"operations:RECEIPT_1",
                "2".repeat(64),operationCount,"3".repeat(64),sourceRevision,
                committedEventSequence,true,
                authorization);
    }

    private static ReviewPacketAuthorizationView authorization(String actionHash) {
        return authorization(actionHash,3);
    }

    private static ReviewPacketAuthorizationView authorization(
            String actionHash,long processRevision) {
        OffsetDateTime committedAt=OffsetDateTime.of(2026,7,24,9,0,0,0,ZoneOffset.UTC);
        return new ReviewPacketAuthorizationView(
                "review-packet-authorization.v1","CASE_1","TASK_1",reviewerAuthorityHash(),
                "PACKET_1",2,"a".repeat(64),actionHash,"PENDING","policy-v1",
                committedAt.minusHours(1),committedAt.plusHours(1),4,processRevision,7,
                Map.of("packet","PACKET_1"));
    }

    private static String reviewerAuthorityHash() {
        return ReviewDecisionReceiptTestFixture.reviewerAuthorityHash("reviewer-local");
    }
}
