package com.example.device.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 死信丢弃入参（契约 {@code discardDeadLetter} 的 {@code {reason}}）。
 * <p>
 * 丢弃是不可逆的业务裁决，必须留理由。当前死信表没有 {@code remark} 列（{@code data-model} §6.5），
 * 因此理由随结构化日志留痕（含 {@code trace_id}），库内只记 {@code handle_user/handle_time/status}。
 * </p>
 *
 * @param reason 丢弃原因
 * @author houzhangbo
 * @since 2026-10-29
 */
public record DeadLetterDiscardRequest(@NotBlank @Size(max = 500) String reason) {
}
