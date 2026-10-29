package com.example.device.core.query;

import com.example.device.core.contract.PayloadLimits;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import java.time.LocalDateTime;

/**
 * EP-003 后台列表的筛选与分页条件。
 * <p>
 * 状态列（{@code online_status} 等）区分度极低、单独筛选不走索引（{@code data-model} §6.1 注记 2），
 * 因此本类把"至少一个高区分度条件"和"深翻页上限"做成进入 Mapper 前的硬校验，
 * 而不是靠补索引救查询。
 * </p>
 *
 * @param sn               精确 SN
 * @param snPrefix         SN 前缀（右模糊，可走 {@code uk_sn} 前缀扫描）
 * @param modelCode        型号编码
 * @param onlineStatus     在线状态，非高区分度条件
 * @param produceTimeStart 生产时间起（含）
 * @param produceTimeEnd   生产时间止（含）
 * @param includeDeleted   是否包含已逻辑删除行
 * @param page             页码，从 1 开始
 * @param pageSize         每页条数
 * @param maxOffset        深翻页上限，来自 {@code device.admin.max-offset}
 * @author houzhangbo
 * @since 2026-10-29
 */
public record DeviceQueryFilter(String sn, String snPrefix, String modelCode, Integer onlineStatus,
                                LocalDateTime produceTimeStart, LocalDateTime produceTimeEnd,
                                boolean includeDeleted, int page, int pageSize, int maxOffset) {

    /** SN 前缀最短长度：过短的前缀区分度不足，等价于全表扫。 */
    public static final int MIN_SN_PREFIX_LENGTH = 4;

    /** 单页最大条数：防止调用方用 {@code page_size=100000} 绕过深翻页限制。 */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * 是否带高区分度条件。
     * <p>
     * 只按状态列或只按时间范围都不算：前者区分度极低，后者在存量表上是全表扫。
     * </p>
     *
     * @return true 表示允许执行本查询
     */
    public boolean hasDiscriminatingCondition() {
        return notBlank(sn) || notBlank(snPrefix) || notBlank(modelCode);
    }

    /**
     * Offset 分页的偏移量。
     *
     * @return {@code (page - 1) * pageSize}
     */
    public int offset() {
        return (page - 1) * pageSize;
    }

    /**
     * 进入 Mapper 前的自校验：条件缺失与深翻页越界在这里转成业务错误码，Controller 不再重复判断。
     */
    public void validate() {
        if (!hasDiscriminatingCondition()) {
            throw new BizException(ErrorCode.MISSING_FILTER_CONDITION);
        }
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR);
        }
        if (offset() > maxOffset) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR);
        }
        if (notBlank(sn) && !matchesSn(sn)) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR);
        }
        if (notBlank(snPrefix) && snPrefix.length() < MIN_SN_PREFIX_LENGTH) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR);
        }
        if (notBlank(snPrefix) && !matchesSn(snPrefix)) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR);
        }
        if (produceTimeStart != null && produceTimeEnd != null && produceTimeEnd.isBefore(produceTimeStart)) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR);
        }
    }

    /**
     * 右模糊用的转义前缀。
     * <p>
     * SN 允许下划线，而 {@code LIKE} 里下划线是单字符通配符——不转义的话 {@code PB_1} 会命中 {@code PB21A}。
     * 入参已由 {@link #validate()} 限定在 SN 字符集内（不含 {@code %} 与反斜杠），因此只需转义下划线。
     * </p>
     *
     * @return 转义后的前缀，无前置条件时返回 {@code null}
     */
    public String escapedSnPrefix() {
        return notBlank(snPrefix) ? snPrefix.replace("_", "\\_") : null;
    }

    private static boolean matchesSn(String value) {
        return value.length() <= PayloadLimits.MAX_SN_LENGTH
                && value.matches(PayloadLimits.SN_PATTERN);
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
