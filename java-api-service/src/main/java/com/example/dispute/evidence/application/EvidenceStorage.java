package com.example.dispute.evidence.application;

public interface EvidenceStorage {

    StoredObject storeOriginal(
            String caseId,
            String evidenceId,
            String filename,
            String contentType,
            byte[] content);

    byte[] loadOriginal(String bucket, String objectKey);

    record StoredObject(String bucket, String objectKey) {}
}
