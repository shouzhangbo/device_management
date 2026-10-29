package com.example.device.core.config;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 优雅停机的<b>在途排空</b>闸门（{@code tech-design} R15）。
 * <p>
 * 停机四步是"先停止拉取 → 等在途消息到终态 → 提交位点 → 关容器"。前两步由 Spring Boot 的
 * {@code server.shutdown=graceful} 与 spring-kafka 容器自身的停止逻辑承担；本类补的是第三步的
 * <b>可观测上界</b>：一个 phase 极低的 {@link SmartLifecycle}，在所有标准生命周期组件之后停止，
 * 检查"是否还有消息正在处理"，有则有界等待。
 * </p>
 * <p>
 * phase 取极小值是刻意的：Spring 按 phase <b>从大到小</b>依次 stop，phase 越小越晚停。
 * 闸门必须晚于 Web 容器与 Kafka 监听容器停止，否则会变成"先切断在途处理再停拉取"的反向顺序。
 * DataSource/Redis 属于 bean 销毁阶段，发生在全部生命周期 stop 之后，天然更晚。
 * </p>
 * <p>
 * 不用 {@code Runtime.addShutdownHook} 手工关组件：钩子与 Spring 的停止顺序无关，无法保证早于 bean 销毁。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Configuration
public class ShutdownPhaseConfig {

    /** 排空闸门的 phase：远小于 Web/ Kafka 容器的 phase，因此在它们之后停止。 */
    public static final int PHASE_DRAIN_GATE = Integer.MIN_VALUE + 1024;

    private static final Logger LOG = LoggerFactory.getLogger(ShutdownPhaseConfig.class);

    /**
     * 在途处理计数：消费处理器进入时 +1、终态后 -1。
     *
     * @return 计数器
     */
    @Bean
    public InflightCounter deviceInflightCounter() {
        return new InflightCounter();
    }

    /**
     * 停止时等待在途消息到终态的闸门。
     *
     * @param counter  在途计数
     * @param ingest   提供等待上界（重试预算 + 事务超时，都是已登记的键）
     * @return 生命周期闸门
     */
    @Bean
    public SmartLifecycle deviceDrainGate(InflightCounter counter, DeviceIngestProperties ingest) {
        long boundMillis = ingest.getRetryBudgetMs() + ingest.getTxTimeoutSec() * 1000L;
        return new DrainGate(counter, boundMillis);
    }

    /**
     * 在途消息计数。
     *
     * @author houzhangbo
     * @since 2026-10-29
     */
    public static class InflightCounter {

        private final AtomicInteger inflight = new AtomicInteger();

        /**
         * 一条消息进入处理。
         */
        public void start() {
            inflight.incrementAndGet();
        }

        /**
         * 一条消息到达终态（成功、放弃或落死信）。
         */
        public void end() {
            inflight.decrementAndGet();
        }

        /**
         * 当前在途数量。
         *
         * @return 计数
         */
        public int get() {
            return inflight.get();
        }
    }

    /**
     * 排空闸门：{@code stop()} 有界等待在途归零。
     * <p>
     * 等待上界取"重试预算 + 事务超时"，这是单条消息最坏耗时；超过它还不归零说明有处理线程卡死，
     * 继续等只会让 K8s 的 SIGKILL 到点强杀，因此打 ERROR 后放行。
     * </p>
     */
    static final class DrainGate implements SmartLifecycle {

        private static final long POLL_MILLIS = 50L;

        private final InflightCounter counter;
        private final long boundMillis;
        private volatile boolean running;

        DrainGate(InflightCounter counter, long boundMillis) {
            this.counter = counter;
            this.boundMillis = boundMillis;
            this.running = false;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop(Runnable callback) {
            stop();
            callback.run();
        }

        @Override
        public void stop() {
            running = false;
            long deadline = System.currentTimeMillis() + boundMillis;
            try {
                while (counter.get() > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(POLL_MILLIS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int left = counter.get();
            if (left > 0) {
                LOG.error("shutdown drain timed out, inflight={} boundMs={}", left, boundMillis);
            } else {
                LOG.info("shutdown drain completed, inflight=0");
            }
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return PHASE_DRAIN_GATE;
        }
    }
}
