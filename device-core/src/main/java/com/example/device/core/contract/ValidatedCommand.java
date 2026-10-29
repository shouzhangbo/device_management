package com.example.device.core.contract;

import com.example.device.core.applier.ApplyCommand;
import com.example.device.core.applier.ColumnBinding;
import com.example.device.core.dim.DimSpec;
import com.example.device.core.dim.DimType;
import com.example.device.core.dim.MsgType;
import java.util.List;

/**
 * 校验通过后的消息：维度与列绑定已确定，{@code DeviceInfoApplier} 不再接触原始 JSON。
 * <p>
 * {@code _ext} 在校验阶段就被取出并序列化为 JSON 文本，因此它<b>不可能</b>以列名身份进入
 * {@code <set>}（{@code tech-design} R14）。
 * </p>
 *
 * @param sn       设备序列号
 * @param source   来源系统编码
 * @param msgId    消息 ID
 * @param msgType  消息类型
 * @param dim      本次触及的维度
 * @param columns  列绑定，全部落在 {@code dim} 白名单内
 * @param extJson  {@code data._ext} 的原始 JSON 文本，无则为 {@code null}
 * @author houzhangbo
 * @since 2026-10-29
 */
public record ValidatedCommand(
        String sn,
        String source,
        String msgId,
        MsgType msgType,
        DimType dim,
        List<ColumnBinding> columns,
        String extJson) {

    /**
     * 列名清单，供日志与流水使用。
     *
     * @return 列名
     */
    public List<String> columnNames() {
        return columns.stream().map(ColumnBinding::column).toList();
    }

    /**
     * 转成写侧入参。
     *
     * @param topic 来源 Topic，由消费侧传入（core 不依赖 Kafka 类型）
     * @return 交给唯一事务入口的命令
     */
    public ApplyCommand toApplyCommand(String topic) {
        return new ApplyCommand(
                sn, dim, msgType.name(), source, msgId, columns, extJson,
                DimSpec.createsDevice(msgType), null, "", topic);
    }
}
