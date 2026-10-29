---
name: Python Redis 开发规范
version: 1.0.0
description: 面向 AI Agent 的 Python Redis 开发规范。覆盖驱动选择（redis-py / aioredis / redis.asyncio）、连接池管理（ConnectionPool）、同步与异步 API、Key 命名规范（业务前缀:实体:ID:字段）、数据结构选型（String / Hash / List / Set / ZSet / Stream / HyperLogLog）、常用模式（分布式锁 / 计数器 / 限流 / 排行榜 / 布隆过滤器）、缓存使用规范（缓存更新策略 / 缓存击穿 / 雪崩 / 穿透）、TTL 管理、Pipeline 批量、事务（MULTI/EXEC）、Lua 脚本原子操作、Pub/Sub、Cluster 集群模式、内存淘汰策略、慢查询日志、Key 过期事件、SCAN vs KEYS。Python 3.x 默认支持。
---

# Python Redis 开发规范

## 前言

Redis 是最流行的内存数据库，Python 生态以 `redis-py` 官方客户端为主，自 4.2+ 起内置 `redis.asyncio` 异步支持。本文档基于 redis-py 官方文档与阿里巴巴 Redis 规范（参见 `redis_specification.md`）提炼最佳实践，为 Python 项目提供正反例对照。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。

> Python 3.x 默认支持；本规范不涉及 Python 2.x 兼容代码。

## 一、驱动与连接池

### 1.1 驱动选择

**【推荐】使用官方 `redis-py` 客户端（>=4.2）；异步场景使用 `redis.asyncio`。**

### 1.2 必须使用连接池

**【强制】所有生产代码必须通过 `ConnectionPool` 复用连接，禁止每次请求新建连接。**

**反例：**
```python
# ❌ 错误：每次请求新建连接
def get_user(user_id):
    r = redis.Redis(host="localhost", port=6379, db=0)  # 新建 TCP 连接
    cache_key = f"user:{user_id}"
    cached = r.get(cache_key)
    ...
```

**正例（同步）：**
```python
# ✅ 正确：模块级连接池
import redis

redis_pool = redis.ConnectionPool(
    host=settings.REDIS_HOST,
    port=settings.REDIS_PORT,
    db=settings.REDIS_DB,
    password=settings.REDIS_PASSWORD,
    decode_responses=True,       # 自动解码为 str
    max_connections=50,
    socket_keepalive=True,
    health_check_interval=30,
)

def get_redis() -> redis.Redis:
    return redis.Redis(connection_pool=redis_pool)
```

**正例（异步）：**
```python
# ✅ 正确：异步连接池
import redis.asyncio as redis_async

async def init_redis():
    return redis_async.Redis(
        host=settings.REDIS_HOST,
        port=settings.REDIS_PORT,
        db=settings.REDIS_DB,
        decode_responses=True,
        max_connections=50,
    )

redis_client: redis_async.Redis = None  # 由 lifespan 初始化
```

## 二、Key 命名规范

**【强制】Key 必须采用 `业务前缀:实体:ID:字段` 的冒号分隔结构，全部小写，禁止空格 / 特殊字符。**

**反例：**
```
❌ userInfo:10001
❌ user info:10001
❌ USER:10001
❌ U_10001_name
```

**正例：**
```
✅ user:10001:profile
✅ order:20260811:items
✅ cache:user:10001
✅ lock:order:20260811:10086
✅ rate_limit:api:login:192.168.1.1
```

**【推荐】Key 总长度 ≤ 64 字符；过长的 Key 会占用更多内存且降低查找效率。**


## 三、数据结构选型

**【强制】根据业务场景选择最优数据结构，禁止滥用 String 存 JSON。**

| 场景 | 推荐数据结构 | Key 示例 |
|------|--------------|----------|
| 单值缓存 | String | `user:10001:profile` |
| 对象多字段 | Hash | `user:10001` (field=name, value=Alice) |
| 队列 | List | `task:queue:email` |
| 标签 / 唯一集合 | Set | `user:10001:tags` |
| 排行榜 | ZSet | `rank:game:score` |
| 计数器 | String (INCR/DECR) | `counter:api:call` |
| 分布式锁 | String | `lock:order:10086` |
| 消息流 | Stream | `stream:events` |
| 去重 | Set / HyperLogLog | `dedup:pv:20260811` |


## 四、连接生命周期管理

### 4.1 同步连接

**【强制】同步 Redis 客户端对象可以模块级复用（连接池内部管理），不需要每次 `close()`。**

### 4.2 异步连接

**【强制】异步 Redis 客户端必须通过 FastAPI `lifespan` 管理生命周期，应用启动时初始化、关闭时优雅释放。**

**正例：**
```python
# app/main.py
from contextlib import asynccontextmanager

@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期：启动初始化 Redis 连接，关闭释放连接池。"""
    global redis_client
    redis_client = redis_async.Redis(
        host=settings.REDIS_HOST,
        port=settings.REDIS_PORT,
        decode_responses=True,
        max_connections=50,
    )
    await redis_client.ping()  # 启动健康检查
    yield
    await redis_client.aclose()  # 优雅关闭

app = FastAPI(lifespan=lifespan)
```


## 五、缓存使用规范

### 5.1 Cache Aside（旁路缓存）

**【推荐】采用 Cache Aside 模式：读时先查 Redis，miss 后查 MySQL 并回填；写时先更新 MySQL 再删除 Redis。**

**正例：**
```python
async def get_user(user_id: int) -> dict:
    """获取用户（Cache Aside）。"""
    cache_key = f"user:{user_id}:profile"

    # 1. 先查 Redis
    cached = await redis_client.get(cache_key)
    if cached:
        return json.loads(cached)

    # 2. miss 后查 MySQL
    user = await user_repository.get_by_id(user_id)
    if not user:
        return None

    # 3. 回填 Redis，TTL 5 分钟
    user_dict = user.to_dict()
    await redis_client.setex(cache_key, 300, json.dumps(user_dict))
    return user_dict

async def update_user(user_id: int, data: dict) -> None:
    """更新用户：先写 MySQL，再删 Redis。"""
    await user_repository.update(user_id, data)
    cache_key = f"user:{user_id}:profile"
    await redis_client.delete(cache_key)  # 失效缓存，下次读 miss 后回填
```

### 5.2 防止缓存击穿

**【推荐】对热点 Key（访问频率极高）使用互斥锁 / singleflight，避免并发 miss 时一起打到 DB：**

**正例：**
```python
import asyncio

async def get_hot_user(user_id: int) -> dict:
    """热点用户查询：单飞（singleflight）防止击穿。"""
    cache_key = f"hot:user:{user_id}"
    cached = await redis_client.get(cache_key)
    if cached:
        return json.loads(cached)

    # SETNX 抢锁
    lock_key = f"lock:{cache_key}"
    got_lock = await redis_client.set(lock_key, "1", nx=True, ex=10)
    if got_lock:
        try:
            user = await user_repository.get_by_id(user_id)
            if user:
                await redis_client.setex(cache_key, 300, json.dumps(user.to_dict()))
                return user.to_dict()
        finally:
            await redis_client.delete(lock_key)
    else:
        # 未抢到锁：短暂 sleep 后重试
        await asyncio.sleep(0.1)
        return await get_hot_user(user_id)
```

### 5.3 防止缓存雪崩

**【推荐】大量 Key 同时过期会导致雪崩；为 Key TTL 增加 ±5 分钟随机抖动：**

```python
import random

ttl = 300 + random.randint(-30, 30)  # 270 ~ 330 秒
await redis_client.setex(cache_key, ttl, value)
```

### 5.4 防止缓存穿透

**【推荐】对于查询返回 None 的结果也缓存一个空标记（如 `__NULL__`），TTL 较短（60s）：**

```python
if not user:
    await redis_client.setex(cache_key, 60, "__NULL__")
    return None

cached = await redis_client.get(cache_key)
if cached == "__NULL__":
    return None
```


## 六、分布式锁

**【推荐】使用 `SET key value NX EX seconds` 原子命令获取锁，Lua 脚本保证释放的原子性：**

**正例：**
```python
# 1. 加锁
async def acquire_lock(lock_key: str, holder: str, ttl: int = 30) -> bool:
    """获取分布式锁。"""
    return await redis_client.set(lock_key, holder, nx=True, ex=ttl)

# 2. 释放锁（Lua 脚本原子检查持有者）
RELEASE_LOCK_LUA = """
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
else
    return 0
end
"""

async def release_lock(lock_key: str, holder: str) -> bool:
    """释放锁：只有持有者才能释放。"""
    result = await redis_client.eval(RELEASE_LOCK_LUA, 1, lock_key, holder)
    return result == 1
```

**【强制】禁止使用 `SETEX + DEL` 实现锁，存在误删他人锁的风险。**


## 七、Pipeline 与批量

**【推荐】批量命令使用 Pipeline 减少网络往返：**

**正例：**
```python
async def batch_get_user_profiles(user_ids: list[int]) -> list[dict | None]:
    """批量获取用户档案。"""
    pipe = redis_client.pipeline()
    for uid in user_ids:
        pipe.get(f"user:{uid}:profile")
    values = await pipe.execute()

    results = []
    for uid, raw in zip(user_ids, values):
        if raw is None:
            results.append(None)
        else:
            results.append(json.loads(raw))
    return results
```

## 八、Lua 脚本

**【推荐】涉及多个 Redis 命令的复合操作使用 Lua 脚本保证原子性：**

**正例（库存扣减）：**
```lua
-- decr_stock.lua
local stock_key = KEYS[1]
local count = tonumber(ARGV[1])
local current = tonumber(redis.call("get", stock_key))
if current == nil then
    return -1  -- key 不存在
end
if current < count then
    return 0  -- 库存不足
end
return redis.call("decrby", stock_key, count)
```

```python
DECR_STOCK_SCRIPT = redis_client.register_script(DECR_STOCK_LUA)
result = await DECR_STOCK_SCRIPT(keys=["stock:item:10086"], args=[1])
```


## 九、Key 过期与 SCAN

### 9.1 禁止 KEYS

**【强制】禁止在生产环境使用 `KEYS *`（会阻塞 Redis 主线程），必须使用 `SCAN` 迭代：**

**反例：**
```python
# ❌ 阻塞 Redis，线上禁止
keys = redis_client.keys("user:*")
for k in keys:
    redis_client.delete(k)
```

**正例：**
```python
# ✅ 正确：使用 SCAN
async def delete_by_pattern(pattern: str) -> int:
    """按模式删除 Key。"""
    count = 0
    async for key in redis_client.scan_iter(match=pattern, count=500):
        await redis_client.delete(key)
        count += 1
    return count
```

### 9.2 批量删除

**【推荐】批量删除使用 `UNLINK`（异步释放内存）而非 `DEL`：**

```python
async def unlink_keys(keys: list[str]) -> int:
    return await redis_client.unlink(*keys)
```


## 十、事务

**【参考】Redis 事务通过 `MULTI/EXEC` 实现，但不支持回滚；推荐使用 Pipeline + Lua 替代。**

**反例：**
```python
# ❌ 事务中命令失败不回滚
pipe = redis_client.pipeline()
pipe.multi()
pipe.set("a", 1)
pipe.incr("a")  # 若此命令参数错误，事务不回滚
pipe.execute()
```


## 十一、敏感操作日志

**【强制】所有写命令（SET / DEL / INCR / EXPIRE 等）必须打印 INFO 日志；DEL 大量 Key 时打印 WARN。**

```python
import logging
logger = logging.getLogger(__name__)

async def delete_user_cache(user_id: int) -> None:
    cache_key = f"user:{user_id}:*"
    deleted = await delete_by_pattern(cache_key)
    logger.info("清除用户缓存 user_id=%s deleted=%d", user_id, deleted)
```


## 十二、内存与淘汰策略

**【推荐】生产 Redis 配置以下参数：**
- `maxmemory 4gb`（按业务容量调整）
- `maxmemory-policy allkeys-lru`（常规业务）或 `volatile-lru`（仅淘汰有过期时间的 Key）
- `maxmemory-samples 10`（LRU 采样精度）

**【强制】禁止将 Redis 当持久化数据库使用；重要数据必须有 MySQL 兜底 + 异步回写策略。**


## 十三、监控指标

**【推荐】通过 `INFO` 命令采集以下指标并接入 Prometheus：**

| 指标 | 含义 |
|------|------|
| `used_memory` | 内存使用量 |
| `used_memory_peak` | 内存峰值 |
| `connected_clients` | 当前连接数 |
| `total_commands_processed` | 命令总数 |
| `instantaneous_ops_per_sec` | QPS |
| `keyspace_hits` / `keyspace_misses` | 缓存命中/未命中 |
| `slowlog_len` | 慢查询队列长度 |


## 十四、禁止事项

**【强制】禁止以下做法：**

1. ❌ 使用 `KEYS` 命令扫描 Key
2. ❌ 大量 Key 同时过期（雪崩）
3. ❌ 单个 Key 存储超过 10KB（Hash/List/Set 大对象）
4. ❌ Redis 存敏感信息（明文密码 / 身份证 / 银行卡 CVV）
5. ❌ 不设 TTL 的 Key（永久占用内存）
6. ❌ 在事务中混用异步阻塞命令（`BLPOP` 会阻塞整个事务）
7. ❌ 用 String 存 JSON 来"模拟"复杂结构（应该用 Hash）


## 十五、快速检查清单

- [ ] 是否使用 `ConnectionPool` 复用连接？
- [ ] Key 是否遵循 `业务:实体:ID:字段` 命名？
- [ ] 缓存更新是否采用 Cache Aside 模式？
- [ ] 是否对热点 Key 防击穿、雪崩加随机 TTL？
- [ ] 分布式锁是否用 `SET NX EX` + Lua 释放？
- [ ] 批量命令是否使用 Pipeline？
- [ ] 复杂操作是否用 Lua 脚本保证原子性？
- [ ] 是否使用 SCAN 替代 KEYS？
- [ ] 写操作是否记录 INFO 日志？
- [ ] 是否监控内存、QPS、命中率指标？