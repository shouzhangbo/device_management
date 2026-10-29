package com.example.device.core.query;

import com.example.device.core.dto.DevicePublicView;
import java.util.List;

/**
 * 批量查询（EP-002）的结果。
 * <p>
 * {@code missing} 由 {@code items} 里 {@code found=false} 的项<b>同一次装配</b>产出（契约
 * {@code BatchQueryData.missing} 描述行）：实现若在两处分别计算判定，同一份数据会出现两个口径。
 * </p>
 *
 * @param items   与请求 SN 顺序一致逐项返回，命中与否都有条目
 * @param missing {@code found=false} 的 SN 摘要
 * @author houzhangbo
 * @since 2026-10-29
 */
public record BatchQueryResult(List<Item> items, List<String> missing) {

    /**
     * 单个 SN 的判定。
     *
     * @param sn    请求的设备序列号
     * @param found 设备存在且未逻辑删除
     * @param info  {@code found=false} 时为 {@code null}，不返回空对象
     * @author houzhangbo
     * @since 2026-10-29
     */
    public record Item(String sn, boolean found, DevicePublicView info) {
    }

    /**
     * 由条目派生 {@code missing}，保证与 {@code items} 同源。
     *
     * @param items 条目
     * @return 结果
     */
    public static BatchQueryResult of(List<Item> items) {
        List<String> absent = items.stream().filter(i -> !i.found()).map(Item::sn).toList();
        return new BatchQueryResult(items, absent);
    }
}
