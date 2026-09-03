package com.example.dispute.workflow.application.intake;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;

@FunctionalInterface
public interface IntakeThreadIdGenerator {

    String nextThreadId();

    static IntakeThreadIdGenerator uuidV7() {
        return uuidV7(Clock.systemUTC(), new SecureRandom());
    }

    static IntakeThreadIdGenerator uuidV7(Clock clock, SecureRandom random) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(random, "random");
        return () -> {
            long unixMillis = clock.millis();
            if (unixMillis < 0 || unixMillis > 0x0000ffffffffffffL) {
                throw new IllegalStateException("clock is outside the UUIDv7 timestamp range");
            }
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            bytes[0] = (byte) (unixMillis >>> 40);
            bytes[1] = (byte) (unixMillis >>> 32);
            bytes[2] = (byte) (unixMillis >>> 24);
            bytes[3] = (byte) (unixMillis >>> 16);
            bytes[4] = (byte) (unixMillis >>> 8);
            bytes[5] = (byte) unixMillis;
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x70);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            return "grt.v1." + HexFormat.of().formatHex(bytes);
        };
    }
}
