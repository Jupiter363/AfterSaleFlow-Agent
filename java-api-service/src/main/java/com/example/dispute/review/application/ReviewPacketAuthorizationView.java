package com.example.dispute.review.application;

import java.time.OffsetDateTime;
import java.util.Map;

/** Private graph capability binding. The actor is represented only by a salted authority hash. */
public record ReviewPacketAuthorizationView(
        String schemaVersion,
        String caseId,
        String reviewTaskId,
        String reviewerAuthorityHash,
        String packetId,
        int packetVersion,
        String packetContentHash,
        String actionHash,
        String taskStatus,
        String policyVersion,
        OffsetDateTime reviewOpenedAt,
        OffsetDateTime deadline,
        long roomEpoch,
        long processRevision,
        long fencingToken,
        Map<String,String> authorizedArtifactRefs) {

    public ReviewPacketAuthorizationView {
        if(!"review-packet-authorization.v1".equals(schemaVersion))
            throw new IllegalArgumentException("unsupported review packet authorization schema");
        requireText(caseId,"caseId");
        requireText(reviewTaskId,"reviewTaskId");
        requireHash(reviewerAuthorityHash,"reviewerAuthorityHash");
        requireText(packetId,"packetId");
        if(packetVersion<1) throw new IllegalArgumentException("packetVersion must be positive");
        requireHash(packetContentHash,"packetContentHash");
        requireText(actionHash,"actionHash");
        requireText(taskStatus,"taskStatus");
        requireText(policyVersion,"policyVersion");
        if(reviewOpenedAt==null) throw new IllegalArgumentException("reviewOpenedAt is required");
        if(deadline==null) throw new IllegalArgumentException("deadline is required");
        if(!reviewOpenedAt.isBefore(deadline))
            throw new IllegalArgumentException("reviewOpenedAt must be before deadline");
        if(roomEpoch<0||processRevision<0||fencingToken<1)
            throw new IllegalArgumentException("epoch, revision, and fence are invalid");
        authorizedArtifactRefs=authorizedArtifactRefs==null?Map.of():Map.copyOf(authorizedArtifactRefs);
        authorizedArtifactRefs.forEach((category,ref)->{
            requireText(category,"artifact category");
            requireText(ref,"artifact ref");
        });
    }

    private static void requireHash(String value,String name) {
        requireText(value,name);
        if(!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name+" must be SHA-256");
    }

    private static void requireText(String value,String name) {
        if(value==null||value.isBlank()) throw new IllegalArgumentException(name+" is required");
    }
}
