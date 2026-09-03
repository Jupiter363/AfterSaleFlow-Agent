package com.example.dispute.casecore.application;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict UTF-8 encoding for values that participate in durable hashes. */
final class StrictUtf8 {

    private StrictUtf8() {}

    static byte[] encode(String value) {
        try {
            ByteBuffer encoded =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalidUnicode) {
            throw new IllegalArgumentException(
                    "hashed request text must be valid Unicode", invalidUnicode);
        }
    }
}
