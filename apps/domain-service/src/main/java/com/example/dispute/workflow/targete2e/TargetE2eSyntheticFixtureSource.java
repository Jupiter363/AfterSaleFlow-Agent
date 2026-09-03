package com.example.dispute.workflow.targete2e;

import java.util.Objects;

/** Read-only deployment fixture source; activation requests never supply fixture bytes. */
@FunctionalInterface
public interface TargetE2eSyntheticFixtureSource {

  ConfiguredFixture loadConfigured(String fixtureSetId);

  static TargetE2eSyntheticFixtureSource unavailable() {
    return fixtureSetId -> {
      throw new IllegalStateException("synthetic fixture source is unavailable");
    };
  }

  record ConfiguredFixture(String readOnlyPathBinding, byte[] bytes) {

    public ConfiguredFixture {
      readOnlyPathBinding = requirePathBinding(readOnlyPathBinding);
      bytes = Objects.requireNonNull(bytes, "bytes").clone();
      if (bytes.length == 0 || bytes.length > 256 * 1024) {
        throw new IllegalArgumentException("synthetic fixture bytes exceed the bounded size");
      }
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  static String requirePathBinding(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 512
        || value.indexOf('\0') >= 0
        || value.indexOf('\r') >= 0
        || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("synthetic fixture path binding is invalid");
    }
    return value;
  }
}
