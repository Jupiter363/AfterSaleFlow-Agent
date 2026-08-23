/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：定义按当前角色投影的证据目录与材料核验详情。
 * 关键边界：原件不可被摘要替代；模型评分与解释不得被投影层重算或改写。
 */
package com.example.dispute.evidence.application;

import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record RoleScopedEvidenceView(
        String caseId, String initiatorRole, String initiatorId, List<Item> items) {

    public RoleScopedEvidenceView(String caseId, List<Item> items) {
        this(caseId, null, null, items);
    }

    public RoleScopedEvidenceView(String caseId, String initiatorRole, List<Item> items) {
        this(caseId, initiatorRole, null, items);
    }

    public record AssessmentFinding(String findingType, String description) {}

    public record ReviewReasonDetail(String code, String label, String explanation) {}

    public record Item(
            String evidenceId,
            String evidenceType,
            String submittedByRole,
            String submittedById,
            String visibility,
            String contentUrl,
            boolean redacted,
            EvidenceVerificationStatus verificationStatus,
            String verificationFeedback,
            String sourceType,
            String originalFilename,
            String parsedText,
            String submissionStatus,
            OffsetDateTime submittedAt,
            String submissionBatchId,
            Double authenticityScore,
            String authenticityScoreExplanation,
            Double relevanceScore,
            String relevanceScoreExplanation,
            Double completenessScore,
            String completenessScoreExplanation,
            Double assessmentConfidence,
            String assessmentConfidenceExplanation,
            String riskLevel,
            String riskExplanation,
            List<String> sourceBasis,
            String formationTimeAssessment,
            List<AssessmentFinding> findings,
            List<String> limitations,
            List<String> unsupportedClaims,
            boolean requiresHumanReview,
            List<ReviewReasonDetail> reasonDetails,
            String claimedFact,
            boolean truthAttested,
            List<String> attestationScope,
            String partyCapacity,
            String attestationVersion,
            String forgeryConsequenceCode,
            String enforcementGate,
            String assessmentProtocol,
            String assessmentText) {

        public Item {
            sourceBasis = sourceBasis == null ? List.of() : List.copyOf(sourceBasis);
            findings = findings == null ? List.of() : List.copyOf(findings);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            unsupportedClaims =
                    unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
            reasonDetails = reasonDetails == null ? List.of() : List.copyOf(reasonDetails);
            attestationScope =
                    attestationScope == null ? List.of() : List.copyOf(attestationScope);
        }

        /** Minimal constructor used by controller fixtures before an assessment exists. */
        public Item(
                String evidenceId,
                String evidenceType,
                String submittedByRole,
                String visibility,
                String contentUrl,
                boolean redacted,
                EvidenceVerificationStatus verificationStatus,
                Double ignoredConfidenceScore,
                String ignoredConfidenceLevel,
                String verificationFeedback,
                String sourceType,
                String originalFilename,
                String parsedText,
                String submissionStatus,
                OffsetDateTime submittedAt,
                String submissionBatchId) {
            this(
                    evidenceId,
                    evidenceType,
                    submittedByRole,
                    null,
                    visibility,
                    contentUrl,
                    redacted,
                    verificationStatus,
                    verificationFeedback,
                    sourceType,
                    originalFilename,
                    parsedText,
                    submissionStatus,
                    submittedAt,
                    submissionBatchId,
                    null, null, null, null, null, null, null, null,
                    null, null, List.of(), null, List.of(), List.of(), List.of(),
                    false, List.of(), null, false, List.of(), null, null, null, null,
                    null, null);
        }

        public Item(
                String evidenceId,
                String evidenceType,
                String submittedByRole,
                String visibility,
                String contentUrl,
                boolean redacted,
                EvidenceVerificationStatus verificationStatus,
                Double ignoredConfidenceScore,
                String ignoredConfidenceLevel,
                String verificationFeedback,
                String sourceType,
                String originalFilename,
                String parsedText) {
            this(
                    evidenceId,
                    evidenceType,
                    submittedByRole,
                    visibility,
                    contentUrl,
                    redacted,
                    verificationStatus,
                    ignoredConfidenceScore,
                    ignoredConfidenceLevel,
                    verificationFeedback,
                    sourceType,
                    originalFilename,
                    parsedText,
                    "SUBMITTED",
                    null,
                    null);
        }
    }
}
