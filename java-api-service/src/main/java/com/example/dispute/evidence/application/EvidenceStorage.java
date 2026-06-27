package com.example.dispute.evidence.application;

public interface EvidenceStorage {

    StoredObject storeOriginal(
            String caseId,
            String evidenceId,
            String filename,
            String contentType,
            byte[] content);

    record StoredObject(String bucket, String objectKey) {}
}
