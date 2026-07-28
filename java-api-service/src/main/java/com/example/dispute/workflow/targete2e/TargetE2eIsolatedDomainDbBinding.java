package com.example.dispute.workflow.targete2e;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Frozen RFC 8785 binding used to tie target finalization to its isolated Domain database. */
public final class TargetE2eIsolatedDomainDbBinding {

  public static final String SCHEMA_VERSION = "target-e2e-isolated-domain-db-binding.v1";
  public static final String BINDING_KIND = "ISOLATED_DOMAIN_POSTGRESQL";

  private TargetE2eIsolatedDomainDbBinding() {}

  public static String hash(
      String environmentId,
      long environmentGeneration,
      String activationId,
      String clusterIdentity,
      String databaseIdentity,
      String runtimePrincipalIdentity) {
    return ContractJson.sha256Hex(
        preimage(
            environmentId,
            environmentGeneration,
            activationId,
            clusterIdentity,
            databaseIdentity,
            runtimePrincipalIdentity));
  }

  public static ObjectNode document(
      String environmentId,
      long environmentGeneration,
      String activationId,
      String clusterIdentity,
      String databaseIdentity,
      String runtimePrincipalIdentity) {
    ObjectNode document =
        preimage(
            environmentId,
            environmentGeneration,
            activationId,
            clusterIdentity,
            databaseIdentity,
            runtimePrincipalIdentity);
    document.put("binding_hash", ContractJson.sha256Hex(document));
    return document;
  }

  public static ObjectNode preimage(
      String environmentId,
      long environmentGeneration,
      String activationId,
      String clusterIdentity,
      String databaseIdentity,
      String runtimePrincipalIdentity) {
    TargetE2eActivationContract.identifier(environmentId, "environmentId");
    TargetE2eActivationContract.generation(environmentGeneration);
    TargetE2eActivationContract.activationId(activationId);
    TargetE2eActivationContract.identifier(clusterIdentity, "clusterIdentity");
    TargetE2eActivationContract.identifier(databaseIdentity, "databaseIdentity");
    TargetE2eActivationContract.identifier(runtimePrincipalIdentity, "runtimePrincipalIdentity");

    ObjectNode preimage = JsonNodeFactory.instance.objectNode();
    preimage.put("schema_version", SCHEMA_VERSION);
    preimage.put("environment_id", environmentId);
    preimage.put("environment_generation", environmentGeneration);
    preimage.put("activation_id", activationId);
    preimage.put("binding_kind", BINDING_KIND);
    preimage.put("cluster_identity", clusterIdentity);
    preimage.put("database_identity", databaseIdentity);
    preimage.put("runtime_principal_identity", runtimePrincipalIdentity);
    return preimage;
  }
}
