package com.example.device.admin.controller;

import com.example.device.admin.config.AdminProperties;
import com.example.device.admin.dto.DeadLetterDiscardRequest;
import com.example.device.admin.security.OperatorContext;
import com.example.device.admin.web.RequestRules;
import com.example.device.core.dto.DeadLetterDetail;
import com.example.device.core.dto.DeadLetterItem;
import com.example.device.core.dto.DeadLetterReplayResult;
import com.example.device.core.dto.PageData;
import com.example.device.core.service.DeadLetterService;
import com.example.device.core.service.DeadLetterStatus;
import com.example.device.core.web.ApiEnvelope;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 死信运维入口（EP-011）：列表 / 详情 / 重放 / 丢弃。
 * <p>
 * 重放复用消费侧同一条链（解析、校验、授权、唯一事务入口），不提供"手工改库"后门；
 * 丢弃只做状态流转，必须带理由（理由随结构化日志留痕，见 {@link DeadLetterDiscardRequest}）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@RestController
@RequestMapping("/api/v1/admin/mq/dead-letters")
public class DeadLetterController {

    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterController.class);

    private static final int MAX_SOURCE_LENGTH = 64;

    private final DeadLetterService deadLetterService;
    private final OperatorContext operatorContext;
    private final AdminProperties adminProperties;

    /**
     * @param deadLetterService 死信服务
     * @param operatorContext   当前操作人
     * @param adminProperties   深翻页上限
     */
    public DeadLetterController(DeadLetterService deadLetterService, OperatorContext operatorContext,
                                AdminProperties adminProperties) {
        this.deadLetterService = deadLetterService;
        this.operatorContext = operatorContext;
        this.adminProperties = adminProperties;
    }

    /**
     * 列表：默认只看待处理（{@code status=0}），不含 {@code content}。
     *
     * @param status   处理状态 0/1/2，默认 0
     * @param source   来源编码，可选
     * @param page     页码
     * @param pageSize 每页条数
     * @return 分页死信
     */
    @GetMapping
    public ApiEnvelope<PageData<DeadLetterItem>> list(
            @RequestParam(name = "status", defaultValue = "0") Integer status,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        requireStatus(status);
        RequestRules.requirePaging(page, pageSize);
        if (source != null && source.length() > MAX_SOURCE_LENGTH) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, "source is too long");
        }
        return ApiEnvelope.success(deadLetterService.page(status, source, page, pageSize,
                adminProperties.maxOffsetOrDefault()));
    }

    /**
     * 详情：附原始消息体。
     *
     * @param id 死信主键
     * @return 详情
     */
    @GetMapping("/{id}")
    public ApiEnvelope<DeadLetterDetail> detail(@PathVariable("id") long id) {
        return ApiEnvelope.success(deadLetterService.detail(id));
    }

    /**
     * 重放：成功即按 EP-006 同一链路落地。
     *
     * @param id 死信主键
     * @return 落地结果
     */
    @PostMapping("/{id}/replay")
    public ApiEnvelope<DeadLetterReplayResult> replay(@PathVariable("id") long id) {
        return ApiEnvelope.success(deadLetterService.replay(id, operatorContext.current()));
    }

    /**
     * 丢弃：不可逆，必须给出理由。
     *
     * @param id      死信主键
     * @param request 丢弃理由
     * @return {@code {id, status: 2}}
     */
    @PostMapping("/{id}/discard")
    public ApiEnvelope<DiscardResult> discard(@PathVariable("id") long id,
                                              @Valid @RequestBody DeadLetterDiscardRequest request) {
        String operator = operatorContext.current();
        deadLetterService.discard(id, operator);
        LOG.info("dead letter discarded, id={}, operator={}, reason={}", id, operator, request.reason());
        return ApiEnvelope.success(new DiscardResult(id, DeadLetterStatus.DISCARDED.code()));
    }

    private static void requireStatus(Integer status) {
        if (status == null || (status != DeadLetterStatus.PENDING.code()
                && status != DeadLetterStatus.REPLAYED.code()
                && status != DeadLetterStatus.DISCARDED.code())) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, "status must be 0, 1 or 2");
        }
    }

    /**
     * 丢弃成功的响应体（契约的 {@code data} 只含 {@code id} 与 {@code status}）。
     *
     * @param id     死信主键
     * @param status 恒为 2（已丢弃）
     * @author houzhangbo
     * @since 2026-10-29
     */
    public record DiscardResult(long id, int status) {
    }
}
