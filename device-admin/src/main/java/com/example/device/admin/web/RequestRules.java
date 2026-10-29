package com.example.device.admin.web;

import com.example.device.core.contract.PayloadLimits;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 后台入口的入参硬校验：契约里写了但 Bean Validation 表达不了（路径参数、分页组合）的约束集中在这里。
 * <p>
 * 分页上限取契约 {@code PageSize} 的 {@code maximum: 100}；core 的 {@code DeviceQueryFilter} 另有
 * 200 的上限是服务层防线，两层都保留：入口挡住契约外的值，服务层挡住内部调用绕过入口的情况。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public final class RequestRules {

    /** 契约 {@code PageSize.maximum}。 */
    public static final int MAX_PAGE_SIZE = 100;

    private RequestRules() {
    }

    /**
     * 分页参数校验。
     *
     * @param page     页码，从 1 起
     * @param pageSize 每页条数
     */
    public static void requirePaging(int page, int pageSize) {
        if (page < 1) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, "page must be >= 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR,
                    "page_size must be within [1, " + MAX_PAGE_SIZE + "]");
        }
    }

    /**
     * 路径 SN 校验：与契约 {@code Sn} 的 pattern/长度同串。
     *
     * @param sn 设备序列号
     */
    public static void requireSn(String sn) {
        if (sn == null || sn.length() > PayloadLimits.MAX_SN_LENGTH || !sn.matches(PayloadLimits.SN_PATTERN)) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, "sn format error");
        }
    }

    /**
     * 带时区偏移的入参 → 库里使用的本地时间。
     *
     * @param value 入参时间，允许为 {@code null}
     * @return 本地时间；入参为 {@code null} 时返回 {@code null}
     */
    public static LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
