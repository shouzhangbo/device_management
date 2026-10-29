package com.example.device.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 错误码表与契约 {@code Error.code} enum 的一致性断言。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class ErrorCodeTest {

    @Test
    void registeredCountMatchesEnumSize() {
        assertEquals(ErrorCode.REGISTERED_COUNT, ErrorCode.values().length);
    }

    @Test
    void codesAreUnique() {
        Set<Integer> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).collect(Collectors.toSet());
        assertEquals(ErrorCode.values().length, codes.size());
    }

    @Test
    void statusIsDerivableFromCode() {
        for (ErrorCode code : ErrorCode.values()) {
            assertEquals(code.status(), code.code() / 10,
                    () -> "code " + code.code() + " must align with HTTP status");
        }
    }

    @Test
    void successCodeIsZero() {
        assertEquals(0, ErrorCode.SUCCESS_CODE);
    }

    @Test
    void messagesAreStableEnglishPhrases() {
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(code.message().matches("[a-z0-9 ,._-]+"),
                    () -> "message must be a stable lowercase English phrase: " + code.message());
        }
    }

    @Test
    void contractCodesAreAllPresent() {
        Set<Integer> expected = Set.of(4001, 4002, 4003, 4004, 4010, 4011, 4030, 4031, 4040, 4050,
                4090, 4091, 4092, 4093, 4290, 5000, 5030);
        Set<Integer> actual = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).collect(Collectors.toSet());
        assertEquals(expected, actual);
    }
}
