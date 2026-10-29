package com.example.device.openapi.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AppKey → Secret 的只读注册表，<b>唯一</b>来源是挂载文件 {@code ${device.openapi.app-secret-path}}。
 * <p>
 * 文件格式是 JSON 对象，值为 secret 数组，顺序为 {@code [当前, 上一版]}：
 * <pre>
 * { "app-key-a": ["current-secret", "previous-secret"], "app-key-b": ["only-secret"] }
 * </pre>
 * 轮换窗口内两个 secret 都参与校验、任一命中即通过；轮换完成把上一版从文件里删掉即可。
 * </p>
 * <p>
 * 缺键、文件不可读、格式非法、单键超过两个 secret、secret 为空串，一律让进程<b>拒绝启动</b>
 * （{@code tech-design} §11.1 / §12.4）：静默回落成"无鉴权"或"空 secret"比启动失败危险得多。
 * 任何情况下不得把 secret 写进日志、异常消息或响应。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class AppKeyRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(AppKeyRegistry.class);

    /** 一个 AppKey 最多两个有效 secret（当前 + 上一版）。 */
    public static final int MAX_SECRETS_PER_KEY = 2;

    private final Map<String, List<String>> secretsByAppKey;

    /**
     * @param secretPath   {@code device.openapi.app-secret-path}，无默认值：缺键即启动失败
     * @param objectMapper 全局 JSON 映射器
     */
    public AppKeyRegistry(@Value("${device.openapi.app-secret-path}") String secretPath,
                          ObjectMapper objectMapper) {
        this.secretsByAppKey = load(secretPath, objectMapper);
        LOG.info("app key registry loaded, keys={}", secretsByAppKey.size());
    }

    /**
     * 该 AppKey 的全部有效 secret。
     *
     * @param appKey 调用方标识
     * @return 只读列表；未知 AppKey 返回空列表
     */
    public List<String> secretsOf(String appKey) {
        if (appKey == null) {
            return List.of();
        }
        return secretsByAppKey.getOrDefault(appKey, List.of());
    }

    /**
     * 当前生效（非轮换上一版）的 secret。
     *
     * @param appKey 调用方标识
     * @return secret；未知 AppKey 返回 {@code null}
     */
    public String secretOf(String appKey) {
        List<String> secrets = secretsOf(appKey);
        return secrets.isEmpty() ? null : secrets.get(0);
    }

    /**
     * 注册的 AppKey 数量，供启动日志与健康检查观察。
     *
     * @return 键数量
     */
    public int size() {
        return secretsByAppKey.size();
    }

    private static Map<String, List<String>> load(String secretPath, ObjectMapper objectMapper) {
        if (secretPath == null || secretPath.isBlank()) {
            throw new IllegalStateException("device.openapi.app-secret-path must not be blank");
        }
        Path path = Path.of(secretPath);
        if (!Files.isReadable(path)) {
            throw new IllegalStateException(
                    "app secret file is not readable: " + secretPath + " (mount the secret file, do not put it in yaml)");
        }
        Map<String, List<String>> raw;
        try {
            raw = objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                    new TypeReference<LinkedHashMap<String, List<String>>>() {
                    });
        } catch (IOException e) {
            throw new IllegalStateException("app secret file is not valid JSON: " + secretPath, e);
        }
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("app secret file contains no app key: " + secretPath);
        }
        Map<String, List<String>> checked = new LinkedHashMap<>(raw.size());
        raw.forEach((appKey, secrets) -> checked.put(appKey, validate(appKey, secrets)));
        return Collections.unmodifiableMap(checked);
    }

    private static List<String> validate(String appKey, List<String> secrets) {
        if (appKey == null || appKey.isBlank() || appKey.length() > 64) {
            throw new IllegalStateException("app secret file contains an invalid app key");
        }
        if (secrets == null || secrets.isEmpty()) {
            throw new IllegalStateException("app secret file has no secret for app key: " + appKey);
        }
        List<String> cleaned = new ArrayList<>(secrets.size());
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                cleaned.add(secret);
            }
        }
        if (cleaned.isEmpty()) {
            throw new IllegalStateException("app secret file has only blank secrets for app key: " + appKey);
        }
        if (cleaned.size() > MAX_SECRETS_PER_KEY) {
            throw new IllegalStateException("app key " + appKey + " has more than " + MAX_SECRETS_PER_KEY
                    + " secrets; keep only [current, previous]");
        }
        return List.copyOf(cleaned);
    }
}
