package com.example.device.ingest.consumer;

import com.example.device.core.config.DeviceIngestProperties;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 事务外层的有界重试：次数、总预算、带 jitter 的指数退避三条同时生效（Spring Boot 规范 §重试【强制】）。
 * <p>
 * 只在<b>事务外</b>循环：每次尝试调一次 {@code DeviceInfoApplier.apply}，即一次独立事务
 * （{@code tech-design} R4）。Applier 内部禁止重试，因为它无法重新取得最新 {@code version}。
 * </p>
 * <p>
 * {@code retry-budget-ms < max.poll.interval.ms} 的不等式由
 * {@code DevicePropertiesValidation} 在启动期断言（三进程共用 core 的那一份，此处不重复断言以免两处口径漂移）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class RetryBudget {

    private static final Logger log = LoggerFactory.getLogger(RetryBudget.class);

    private final DeviceIngestProperties properties;

    /**
     * @param properties 次数 / 预算 / 退避参数
     */
    public RetryBudget(DeviceIngestProperties properties) {
        this.properties = properties;
    }

    /**
     * 重试耗尽。
     * <p>
     * 调用方据此落死信：本异常<b>总是带 cause</b>，因此死信 {@code fail_reason} 有真实原因可写，
     * 不会出现"耗尽但不知道为什么"。
     * </p>
     */
    public static class ExhaustedException extends RuntimeException {

        /**
         * @param attempts 实际尝试次数
         * @param cause    最后一次尝试的失败原因
         */
        public ExhaustedException(int attempts, Throwable cause) {
            super("retry exhausted after " + attempts + " attempts", cause);
        }
    }

    /**
     * 执行一次尝试并在<b>本次失败后</b>回调（CAS 冲突回滚时必须先释放 Redis 幂等键再重试，R3）。
     *
     * @param action   一次尝试 = 一次独立事务
     * @param onFailed 失败回调，允许为 {@code null}
     * @param <T>      返回值类型
     * @return 首次成功的返回值
     * @throws ExhaustedException 次数或预算耗尽
     */
    public <T> T run(Callable<T> action, Runnable onFailed) {
        int maxRetry = properties.getMaxRetry();
        int attempts = maxRetry + 1;
        long budgetMs = properties.getRetryBudgetMs();
        long deadline = System.currentTimeMillis() + budgetMs;
        Throwable last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return action.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExhaustedException(i + 1, e);
            } catch (Exception e) {
                last = e;
                if (onFailed != null) {
                    onFailed.run();
                }
                if (i == attempts - 1) {
                    break;
                }
                long waitMillis = nextBackoffMillis(i + 1, deadline);
                if (waitMillis < 0) {
                    log.warn("retry budget exhausted, attempts={}, budgetMs={}", i + 1, budgetMs);
                    throw new ExhaustedException(i + 1, e);
                }
                sleep(waitMillis);
            }
        }
        throw new ExhaustedException(attempts, last);
    }

    /**
     * 第 {@code attempt} 次失败后的等待毫秒数。
     * <p>
     * 抽成独立方法是为了让"退避必须带 jitter"可被单元测试直接断言（连续 5 次间隔值互不相等）。
     * </p>
     *
     * @param attempt  已失败的尝试序号，从 1 开始
     * @param deadline 预算截止时间戳
     * @return 需要 sleep 的毫秒数；预算已不够下一次退避时返回 {@code -1} 表示该停了
     */
    public long nextBackoffMillis(int attempt, long deadline) {
        long base = properties.getBackoffMs();
        long exponential = base << Math.min(attempt - 1, 16);
        double jitter = properties.getBackoffJitter();
        double ratio = 1.0D + jitter * (2.0D * java.util.concurrent.ThreadLocalRandom.current().nextDouble() - 1.0D);
        long delay = Math.round(exponential * ratio);
        long remain = deadline - System.currentTimeMillis();
        if (remain <= 0) {
            return -1L;
        }
        return Math.min(delay, remain);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExhaustedException(0, e);
        }
    }
}
