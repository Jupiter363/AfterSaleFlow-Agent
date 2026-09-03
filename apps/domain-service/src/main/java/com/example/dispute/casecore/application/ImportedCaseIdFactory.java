package com.example.dispute.casecore.application;

/** Optional deployment-specific authority for server-generated imported case IDs. */
@FunctionalInterface
public interface ImportedCaseIdFactory {

    String nextCaseId();
}
