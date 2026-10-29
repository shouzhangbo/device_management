package com.example.device.core.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 全局 JSON 口径：snake_case 命名 + ISO 8601 带时区的时间输出。
 * <p>
 * 声明<b>一次</b>即可让对外响应与缓存 Value 共用同一份配置（{@code tech-design} §9.4 缓存不变式的前提）：
 * 如果缓存用自己的 mapper、响应用另一套，两侧的时间格式就会漂移，EP-007 对账比的是 {@code update_time}，
 * 格式漂移会让对账把相同数据误判成不一致并反复删缓存。
 * </p>
 * <p>
 * 时间口径（需求方裁决 + 附录 A）：库里存<b>本地</b> {@code DATETIME}，{@code LocalDateTime} 原样进出；
 * 出口在本类转成带时区的 ISO 8601，因此不需要新的时区配置键，取 {@link ZoneId#systemDefault()}。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Configuration
public class JacksonConfig {

    /** 出口时间格式：ISO 8601 带偏移量，例如 {@code 2026-10-29T10:21:05+08:00}。 */
    public static final DateTimeFormatter OUTBOUND = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * 把全局 Jackson 配置接进 Spring Boot 的自动配置，使 Web 层与 {@link ObjectMapper} 注入点共用一份设置。
     *
     * @return 构建器定制器
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer deviceJacksonCustomizer() {
        ZoneId zone = ZoneId.systemDefault();
        return builder -> {
            builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            builder.featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            SimpleModule module = new SimpleModule();
            module.addSerializer(LocalDateTime.class, new OffsetDateTimeSerializer(zone));
            module.addDeserializer(LocalDateTime.class, new OffsetDateTimeDeserializer(zone));
            builder.modulesToInstall(module);
        };
    }

    /**
     * 供非 Web 场景（如流水 {@code before_json} 快照）显式取用的全局 ObjectMapper。
     * <p>
     * 与自动配置出来的实例同源，因此不会出现"日志里是下划线、响应里是驼峰"的分叉。
     * </p>
     *
     * @param builder Spring Boot 提供的、已带上述定制的构建器
     * @return 配置完成的 mapper
     */
    @Bean
    public ObjectMapper deviceObjectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.createXmlMapper(false).build();
    }

    /**
     * {@code LocalDateTime} → 带偏移量的字符串。
     *
     * @author houzhangbo
     * @since 2026-10-29
     */
    static final class OffsetDateTimeSerializer extends JsonSerializer<LocalDateTime> {

        private final ZoneId zone;

        OffsetDateTimeSerializer(ZoneId zone) {
            this.zone = zone;
        }

        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeString(value.atZone(zone).format(OUTBOUND));
        }
    }

    /**
     * 带偏移量或本地格式的字符串 → {@code LocalDateTime}。
     * <p>
     * 容忍两种输入是必要的：缓存 Value 由本类写出（带偏移），而外部消息里的时间字段可能只给本地时间。
     * </p>
     *
     * @author houzhangbo
     * @since 2026-10-29
     */
    static final class OffsetDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

        private final ZoneId zone;

        OffsetDateTimeDeserializer(ZoneId zone) {
            this.zone = zone;
        }

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            String trimmed = text.trim();
            try {
                return java.time.OffsetDateTime.parse(trimmed, OUTBOUND).atZoneSameInstant(zone).toLocalDateTime();
            } catch (DateTimeParseException ignored) {
                return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
        }
    }
}
