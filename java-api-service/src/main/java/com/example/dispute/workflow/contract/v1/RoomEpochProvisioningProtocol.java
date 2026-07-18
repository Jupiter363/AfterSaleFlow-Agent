package com.example.dispute.workflow.contract.v1;

public final class RoomEpochProvisioningProtocol {

  public static final String PROVISION_ROOM_EPOCH_UPDATE = "provisionRoomEpoch";
  public static final String PROVISIONING_STATE_QUERY = "roomEpochProvisioningState";
  public static final String COMMAND_SCHEMA_VERSION = "provision-room-epoch.v1";
  public static final String RECEIPT_SCHEMA_VERSION = "provision-room-epoch-receipt.v1";

  private RoomEpochProvisioningProtocol() {}

  public static String updateId(String epochId, long fencingToken) {
    requireText(epochId, "epochId");
    if (fencingToken < 1) {
      throw new IllegalArgumentException("fencingToken must be positive");
    }
    String value = "bootstrap:" + epochId + ":" + fencingToken;
    if (value.length() > 128) {
      throw new IllegalArgumentException("bootstrap update id exceeds 128 characters");
    }
    return value;
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
