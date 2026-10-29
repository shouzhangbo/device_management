package com.example.device.ingest.config;

import com.example.device.core.config.DeviceIngestProperties;
import java.util.Map;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * 消费容器：手动位点 + 固定并发 + 有界停机。
 * <p>
 * {@link ContainerProperties.AckMode#MANUAL_IMMEDIATE} 是【强制】项（MQ 规范 §2.2）：自动提交会在
 * 事务提交之前推进位点，崩溃即丢消息（R13）。
 * </p>
 * <p>
 * 消费者工厂在这里显式构造而不是注入 Boot 的 {@code ConsumerFactory<?, ?>}：泛型上 Boot 声明的是通配类型，
 * 注入 {@code ConsumerFactory<byte[], byte[]>} 只能靠类型推断碰运气。属性仍全部来自
 * {@code spring.kafka.*}，不含任何私有配置键。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Configuration
public class KafkaListenerConfig {

    /** 容器关闭等待时长：与 {@code spring.lifecycle.timeout-per-shutdown-phase} 同量级，不额外新增配置键。 */
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 30_000L;

    /**
     * 字节级消费者工厂：value 用 {@code ByteArrayDeserializer}，解析交给 {@code EnvelopeParser}，
     * 这样解析失败时死信还能保存原文（R12）。
     *
     * @param kafkaProperties {@code spring.kafka.*} 的类型安全绑定
     * @return 消费者工厂
     */
    @Bean
    public ConsumerFactory<byte[], byte[]> deviceConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * 业务监听容器工厂。
     *
     * @param consumerFactory 字节级消费者工厂
     * @param properties      并发数等参数
     * @return 容器工厂
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<byte[], byte[]> kafkaListenerContainerFactory(
            ConsumerFactory<byte[], byte[]> consumerFactory, DeviceIngestProperties properties) {
        ConcurrentKafkaListenerContainerFactory<byte[], byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(properties.getConcurrency());
        ContainerProperties container = factory.getContainerProperties();
        container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // Topic 由运维前置创建，不在消费端自动建：缺失时继续启动，由 lag 与消费量指标暴露问题。
        container.setMissingTopicsFatal(false);
        container.setShutdownTimeout(SHUTDOWN_TIMEOUT_MILLIS);
        return factory;
    }
}
