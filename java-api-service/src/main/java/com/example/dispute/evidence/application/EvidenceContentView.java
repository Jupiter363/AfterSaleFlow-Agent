package com.example.dispute.evidence.application;

public record EvidenceContentView(
        String filename,
        String contentType,
        byte[] content) {}
