package com.example.device.ingest.consumer;

import com.example.device.core.applier.ApplyOutcome;
import com.example.device.core.auth.DimAuthGuard;
import com.example.device.core.contract.ValidateStatus;
import org.springframework.stereotype.Component;

/**
 * 六个终态与"是否提交位点"的<b>唯一</b>判定表（{@code tech-design} R13）。
 * <p>
 * 位点只在终态后提交；本期唯一允许"不 ack"的分支是<b>落死信本身失败</b>——此时既不丢数据也不丢位点，
 * 让消息重投是唯一安全选择。判定逻辑集中在这里，是为了防止终态与 ack 策略在别处被改出不一致。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class ConsumeOutcomeResolver {

    /** 消费终态。 */
    public enum Outcome {
        /** 主档与流水已提交（终态⑥）。 */
        APPLIED(true),
        /** 设备不存在，只留痕 result=3，事务正常提交（终态⑥ 的另一支，R5）。 */
        MISSING_DEVICE_RECORDED(true),
        /** 重复消息：Redis 挡重或 DB 唯一索引撞键（终态③④）。 */
        DUPLICATE(true),
        /** 未授权来源：计数 + WARN + ack，不写任何库（终态②，R11）。 */
        UNAUTHORIZED(true),
        /** 未知 msg_type：同上（终态②）。 */
        UNKNOWN_MSG_TYPE(true),
        /** 白名单越界 / data 为空等语义非法：WARN + ack，<b>无指标键</b>（终态②；§11.1 未登记该计数器）。 */
        INVALID_PAYLOAD(true),
        /** 不可解析 / 超限 / 重试耗尽，已落死信（终态①⑤）。 */
        DEAD_LETTERED(true),
        /** 落死信本身失败：不提交位点，消息重投。 */
        RECONSUME(false);

        private final boolean ack;

        Outcome(boolean ack) {
            this.ack = ack;
        }

        /**
         * 是否可以在本终态后提交位点。
         *
         * @return true 表示 {@code acknowledge()}
         */
        public boolean shouldAck() {
            return ack;
        }
    }

    /**
     * 事务已提交的 Applier 结果。
     *
     * @param outcome Applier 返回值
     * @return 对应终态
     */
    public Outcome resolve(ApplyOutcome outcome) {
        return switch (outcome) {
            case APPLIED -> Outcome.APPLIED;
            case MISSING_DEVICE_RECORDED -> Outcome.MISSING_DEVICE_RECORDED;
            case DUPLICATE -> Outcome.DUPLICATE;
        };
    }

    /**
     * 校验层结论。
     *
     * @param status 校验状态
     * @return 终态；{@link ValidateStatus#OK} 时返回 {@code null} 表示"继续往下走"
     */
    public Outcome resolveValidation(ValidateStatus status) {
        return switch (status) {
            case OK -> null;
            case UNKNOWN_MSG_TYPE -> Outcome.UNKNOWN_MSG_TYPE;
            case INVALID_PAYLOAD -> Outcome.INVALID_PAYLOAD;
        };
    }

    /**
     * 授权层结论。
     *
     * @param decision 授权判定
     * @return 终态；{@link DimAuthGuard.Decision#ALLOW} 时返回 {@code null} 表示"继续往下走"
     */
    public Outcome resolveAuth(DimAuthGuard.Decision decision) {
        return decision == DimAuthGuard.Decision.ALLOW ? null : Outcome.UNAUTHORIZED;
    }

    /**
     * 死信落库结论。
     *
     * @param written 落库是否成功
     * @return 成功 → {@link Outcome#DEAD_LETTERED}（可 ack）；失败 → {@link Outcome#RECONSUME}（禁止 ack）
     */
    public Outcome resolveDeadLetter(boolean written) {
        return written ? Outcome.DEAD_LETTERED : Outcome.RECONSUME;
    }
}
