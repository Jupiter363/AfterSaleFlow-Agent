package com.example.dispute.workflow.temporal.room.intake;

import java.util.regex.Pattern;

final class IntakeProtocolValidation {

  private static final Pattern IDENTIFIER =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern THREAD_ID = Pattern.compile("grt\\.v1\\.[0-9a-f]{32}");

  private IntakeProtocolValidation() {}

  static String requireIdentifier(String value, String field) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
    return value;
  }

  static String requireHash(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
    }
    return value;
  }

  static String requireThreadId(String value, String field) {
    if (value == null || !THREAD_ID.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be an opaque graph thread id");
    }
    return value;
  }

  static String requireReference(String value, String field) {
    if (value == null
        || value.length() > 1024
        || !(value.startsWith("urn:")
            || value.startsWith("s3:")
            || value.startsWith("minio:"))) {
      throw new IllegalArgumentException(field + " must be an immutable artifact reference");
    }
    return value;
  }
}
