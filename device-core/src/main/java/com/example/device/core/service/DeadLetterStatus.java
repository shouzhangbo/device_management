package com.example.device.core.service;

/**
 * 死信处理状态，取值与 {@code data-model} §6.4 的 {@code status} 列注释逐一对应。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public enum DeadLetterStatus {

    /** 待处理。 */
    PENDING(0),

    /** 已重放。 */
    REPLAYED(1),

    /** 已丢弃。 */
    DISCARDED(2);

    private final int code;

    DeadLetterStatus(int code) {
        this.code = code;
    }

    /**
     * 数据库取值。
     *
     * @return {@code status} 列的整数
     */
    public int code() {
        return code;
    }
}
