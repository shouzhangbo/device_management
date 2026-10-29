package com.example.device.core.cache;

/**
 * 全部 Redis key 的唯一拼装点。
 * <p>
 * 六个 key 的形态与 {@code data-model} §技术栈表 / §附录 一致性检查表同串；
 * 任何一处改前缀都会让写侧删不掉读侧建的缓存，因此禁止在业务代码里手拼字符串。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public final class CacheKeys {

    /** 空值哨兵：与真实 JSON Value 可区分，命中即直接 404，不回源（Redis §2.4【强制】）。 */
    public static final String EMPTY_SENTINEL = "__EMPTY__";

    /** 缓存重建互斥锁的固定 TTL 秒数（设计 §9.2 时序图 {@code EX 3}）。 */
    public static final long REBUILD_LOCK_TTL_SECONDS = 3L;

    private static final String INFO = "device:cache:info:";
    private static final String REBUILD_LOCK = "device:lock:cache:rebuild:";
    private static final String DEDUP = "mq:dedup:";
    private static final String CRON_LOCK = "device:lock:cron:";
    private static final String ADMIN_IDEM = "device:admin:idem:";
    private static final String OPENAPI_QUOTA = "device:openapi:quota:";

    /** 对账水位 key（单值，非按 SN 分片）。 */
    public static final String RECONCILE_WATERMARK = "device:cron:watermark:cache-reconcile";

    private CacheKeys() {
    }

    /**
     * 设备缓存 key。
     *
     * @param sn 设备序列号
     * @return 完整 key
     */
    public static String info(String sn) {
        return INFO + sn;
    }

    /**
     * 缓存重建互斥锁 key。
     *
     * @param sn 设备序列号
     * @return 完整 key
     */
    public static String rebuildLock(String sn) {
        return REBUILD_LOCK + sn;
    }

    /**
     * 消费幂等挡重 key（第一层，<b>不承担正确性</b>）。
     *
     * @param source 来源编码
     * @param msgId  消息 ID
     * @return 完整 key
     */
    public static String dedup(String source, String msgId) {
        return DEDUP + source + ":" + msgId;
    }

    /**
     * 定时任务互斥锁 key。
     *
     * @param job 任务名：{@code cache-reconcile} / {@code archive-cleanup} / {@code partition-prebuild}
     * @return 完整 key
     */
    public static String cronLock(String job) {
        return CRON_LOCK + job;
    }

    /**
     * 后台建档幂等键。
     *
     * @param idempotencyKey 请求头 {@code Idempotency-Key}
     * @return 完整 key
     */
    public static String adminIdem(String idempotencyKey) {
        return ADMIN_IDEM + idempotencyKey;
    }

    /**
     * 对外查询的 AppKey 配额计数键（固定窗口）。
     * <p>
     * 只被 {@code device-openapi} 读写，与缓存/幂等键没有跨进程耦合；放在本类是为了守住
     * "Redis key 只在唯一拼装点生成"这条不变式。
     * </p>
     *
     * @param appKey      调用方标识
     * @param windowEpoch 窗口起点（Unix 秒）
     * @return 完整 key
     */
    public static String openapiQuota(String appKey, long windowEpoch) {
        return OPENAPI_QUOTA + appKey + ":" + windowEpoch;
    }
}
