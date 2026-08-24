package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.targete2e.TargetE2eIsolatedDomainDbBinding;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/** Loads the immutable activation and isolated-domain binding used by finalization evidence. */
@FunctionalInterface
public interface TargetE2eFinalizationEnvironmentSource {

    EnvironmentEvidence loadEnvironmentEvidence();

    record EnvironmentEvidence(
            String activationId,
            String manifestHash,
            String environmentId,
            long environmentGeneration,
            String domainClusterIdentity,
            String domainDatabaseIdentity,
            String domainRuntimePrincipalIdentity,
            String domainDbBindingHash) {

        public EnvironmentEvidence {
            manifestHash = sha256(manifestHash, "manifestHash");
            domainDbBindingHash = sha256(domainDbBindingHash, "domainDbBindingHash");
            ObjectNode document = TargetE2eIsolatedDomainDbBinding.document(
                    environmentId,
                    environmentGeneration,
                    activationId,
                    domainClusterIdentity,
                    domainDatabaseIdentity,
                    domainRuntimePrincipalIdentity);
            if (!domainDbBindingHash.equals(document.required("binding_hash").textValue())) {
                throw new IllegalArgumentException(
                        "domainDbBindingHash differs from the isolated Domain binding");
            }
        }

        public ObjectNode isolatedDomainDbBinding() {
            return TargetE2eIsolatedDomainDbBinding.document(
                    environmentId,
                    environmentGeneration,
                    activationId,
                    domainClusterIdentity,
                    domainDatabaseIdentity,
                    domainRuntimePrincipalIdentity);
        }

        private static String sha256(String value, String field) {
            Objects.requireNonNull(value, field);
            if (!value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
            }
            return value;
        }
    }
}
