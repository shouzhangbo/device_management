package com.example.device.core.dto;

import java.util.List;

/**
 * 分页响应体：契约 {@code PageMeta} 的 {@code {total,page,page_size}} 与 {@code items} 拍平在同一层
 * （HTTP 规范 §6.1 正例）。
 * <p>
 * 分页参数名用 {@code page_size} 而不是 {@code limit/offset}：后者会把"服务端内部实现"暴露成对外契约。
 * </p>
 *
 * @param items    当前页数据
 * @param total    满足条件的总行数
 * @param page     当前页码（回显入参）
 * @param pageSize 每页条数（回显入参）
 * @param <T>      列表项类型
 * @author houzhangbo
 * @since 2026-10-29
 */
public record PageData<T>(List<T> items, long total, int page, int pageSize) {
}
