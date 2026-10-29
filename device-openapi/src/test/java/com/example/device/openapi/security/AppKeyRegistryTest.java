package com.example.device.openapi.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * secret 文件是唯一真源：合法文件按 {@code [当前, 上一版]} 读取，任何异常形态都让进程拒绝启动且不泄漏 secret。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class AppKeyRegistryTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsCurrentAndPreviousSecretsInOrder() throws IOException {
        Path file = write("{ \"app-a\": [\"cur-secret\", \"prev-secret\"], \"app-b\": [\"only-secret\"] }");

        AppKeyRegistry registry = new AppKeyRegistry(file.toString(), objectMapper);

        assertEquals(2, registry.size());
        assertEquals(List.of("cur-secret", "prev-secret"), registry.secretsOf("app-a"));
        assertEquals("cur-secret", registry.secretOf("app-a"));
        assertEquals("only-secret", registry.secretOf("app-b"));
    }

    @Test
    void blanksAreDroppedButAtLeastOneSecretMustRemain() throws IOException {
        Path file = write("{ \"app-a\": [\"cur-secret\", \"  \"] }");

        AppKeyRegistry registry = new AppKeyRegistry(file.toString(), objectMapper);

        assertEquals(List.of("cur-secret"), registry.secretsOf("app-a"));
    }

    @Test
    void unknownOrNullAppKeyHasNoSecret() throws IOException {
        AppKeyRegistry registry = new AppKeyRegistry(write("{ \"app-a\": [\"cur-secret\"] }").toString(),
                objectMapper);

        assertTrue(registry.secretsOf("app-x").isEmpty());
        assertTrue(registry.secretsOf(null).isEmpty());
        assertNull(registry.secretOf("app-x"));
    }

    @Test
    void blankPathFailsStartup() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new AppKeyRegistry("  ", objectMapper));

        assertTrue(thrown.getMessage().contains("app-secret-path"));
    }

    @Test
    void missingFileFailsStartup() {
        String missing = tempDir.resolve("nope.json").toString();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new AppKeyRegistry(missing, objectMapper));

        assertTrue(thrown.getMessage().contains("not readable"));
    }

    @Test
    void invalidJsonFailsStartupWithoutLeakingContent() throws IOException {
        Path file = write("app-a: super-secret-value");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new AppKeyRegistry(file.toString(), objectMapper));

        assertTrue(thrown.getMessage().contains("not valid JSON"));
        assertFalse(containsSecret(thrown, "super-secret-value"));
    }

    @Test
    void emptyObjectFailsStartup() throws IOException {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new AppKeyRegistry(write("{}").toString(), objectMapper));

        assertTrue(thrown.getMessage().contains("no app key"));
    }

    @Test
    void moreThanTwoSecretsFailsStartupWithoutLeakingSecret() throws IOException {
        Path file = write("{ \"app-a\": [\"s1\", \"s2\", \"s3\"] }");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new AppKeyRegistry(file.toString(), objectMapper));

        assertTrue(thrown.getMessage().contains("more than 2 secrets"));
        assertFalse(containsSecret(thrown, "s3"));
    }

    @Test
    void onlyBlankSecretsFailsStartup() throws IOException {
        Path file = write("{ \"app-a\": [\"\", \" \"] }");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new AppKeyRegistry(file.toString(), objectMapper));

        assertTrue(thrown.getMessage().contains("only blank secrets"));
    }

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("app-secrets.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static boolean containsSecret(Throwable thrown, String secret) {
        return thrown.getMessage() != null && thrown.getMessage().contains(secret);
    }
}
