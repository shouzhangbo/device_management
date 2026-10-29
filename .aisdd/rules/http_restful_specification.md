---
name: HTTP RESTful API 设计规范
version: 1.0.0
description: 面向 AI Agent 的 HTTP RESTful API 设计规范。覆盖 REST 架构风格、HTTP 方法语义、URL 资源命名（复数名词 / 层级关系 / 避免动词）、HTTP 状态码语义（2xx / 3xx / 4xx / 5xx 正确使用）、请求方法（GET/POST/PUT/PATCH/DELETE/OPTIONS/HEAD）、幂等性与安全性、请求体（JSON / form-data / multipart）、分页（offset / cursor）、排序与过滤、字段投影（sparse fieldsets）、HATEOAS、版本控制（URL Path / Header）、统一响应结构（{code, message, data}）、统一错误响应（含 trace_id）、限流响应头（X-RateLimit-*）、CORS、缓存控制（ETag / Cache-Control / Last-Modified）、OpenAPI / Swagger 文档、安全（认证 / 授权 / 输入校验 / 限流）。参考 Microsoft REST API Guidelines、Google API Design Guide、Roy Fielding 博士论文与 GitHub / GitLab / Stripe 公开 API 文档。
---

# HTTP RESTful API 设计规范

## 前言

REST（Representational State Transfer）由 Roy Fielding 在 2000 年博士论文中提出，是目前 Web API 设计的事实标准。一个优秀的 RESTful API 应具备：清晰的资源命名、正确的 HTTP 方法语义、合理的状态码使用、一致的响应结构与完善的错误处理。

本文档综合 Microsoft REST API Guidelines、Google API Design Guide、GitHub / GitLab / Stripe 等行业标杆 API 的设计经验，为 AI Agent 生成 HTTP API 接口与文档提供正反例对照。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。

## 一、资源命名

### 1.1 URL 必须使用复数名词

**【强制】资源 URL 必须使用复数名词（users / orders / products），禁止使用单数、动词或动作。**

**反例：**
```
❌ /getUser
❌ /user/create
❌ /fetchOrderList
❌ /deleteUserById/123
```

**正例：**
```
✅ GET    /users          # 获取用户列表
✅ POST   /users          # 创建用户
✅ GET    /users/123      # 获取单个用户
✅ PUT    /users/123      # 更新用户全量字段
✅ PATCH  /users/123      # 更新用户部分字段
✅ DELETE /users/123      # 删除用户
```

### 1.2 层级关系

**【推荐】资源之间的从属关系通过嵌套 URL 表达（最多 3 层），超过 3 层建议改用查询参数过滤。**

**正例：**
```
✅ GET /users/123/orders              # 用户 123 的所有订单
✅ GET /users/123/orders/456          # 用户 123 的订单 456
✅ GET /orders/456/items              # 订单 456 的所有商品
❌ /users/123/orders/456/items/789/categories  # 4 层过深
```

### 1.3 命名风格

**【强制】URL 路径必须全小写，单词之间用连字符 `-` 或下划线 `_` 分隔；查询参数使用 camelCase 或 snake_case（与请求体保持一致）。**

**反例：**
```
❌ /UserOrders
❌ /userOrders
```

**正例：**
```
✅ /user-orders
✅ /user_orders
```

### 1.4 禁止 URL 中包含技术细节

**【强制】禁止在 URL 中暴露技术细节（文件后缀 / 框架标识 / 实现细节）。**

**反例：**
```
❌ /users.json
❌ /api/v1/users.do
❌ /users/list.php
```

**正例：**
```
✅ /api/v1/users
```


## 二、HTTP 方法语义

### 2.1 方法与意图映射

**【强制】严格遵循 HTTP 方法的语义，禁止滥用：**

| 方法 | 语义 | 幂等 | 安全 | 用途 |
|------|------|------|------|------|
| GET | 获取资源 | ✅ | ✅ | 查询，不修改服务端状态 |
| POST | 创建资源 / 触发动作 | ❌ | ❌ | 创建子资源、触发非幂等流程 |
| PUT | 全量更新 | ✅ | ❌ | 用完整对象替换资源 |
| PATCH | 部分更新 | ❌ | ❌ | 修改资源的部分字段 |
| DELETE | 删除资源 | ✅ | ❌ | 删除资源 |
| OPTIONS | 描述资源选项 | ✅ | ✅ | CORS 预检 |
| HEAD | 获取资源元信息 | ✅ | ✅ | 检查资源是否存在 / 缓存新鲜度 |

**幂等定义**：执行 1 次与执行 N 次效果相同。
**安全定义**：不修改服务端状态。

### 2.2 常见滥用

**反例：**
```
❌ GET /users/delete/123         # 用 GET 删除
❌ POST /users/123/update        # 该用 PATCH
❌ POST /users/123/delete        # 该用 DELETE
❌ GET /search?q=...&create=true # GET 不应修改状态
```

**正例：**
```
✅ DELETE /users/123
✅ PATCH  /users/123  body={"nickname": "Alice"}
✅ POST   /users/123/sessions  body={"device": "ios"}  # 创建用户 123 的会话
```


## 三、HTTP 状态码

### 3.1 必须使用精确的状态码

**【强制】响应必须使用最精确的 HTTP 状态码，禁止一律返回 200。**

**反例：**
```json
HTTP/1.1 200 OK
{
  "code": 0,
  "message": "success",
  "data": {"user": null}
}
```

**正例：**
```json
HTTP/1.1 404 Not Found
{
  "code": 404,
  "message": "用户不存在",
  "data": null
}
```

### 3.2 状态码速查

| 类别 | 状态码 | 语义 | 使用场景 |
|------|--------|------|----------|
| 2xx | 200 OK | 成功 | GET / PUT / PATCH 成功 |
| 2xx | 201 Created | 已创建 | POST 创建资源成功 |
| 2xx | 204 No Content | 无内容 | DELETE 成功、PUT 无返回体 |
| 3xx | 301 Moved Permanently | 永久重定向 | 资源永久迁移 |
| 3xx | 304 Not Modified | 缓存命中 | ETag / If-Modified-Since 命中 |
| 4xx | 400 Bad Request | 请求错误 | 客户端参数错误 |
| 4xx | 401 Unauthorized | 未认证 | 缺少身份凭证 |
| 4xx | 403 Forbidden | 无权限 | 已认证但越权 |
| 4xx | 404 Not Found | 资源不存在 | URL 或 ID 不存在 |
| 4xx | 405 Method Not Allowed | 方法不允许 | URL 不支持当前 HTTP 方法 |
| 4xx | 409 Conflict | 资源冲突 | 唯一约束冲突、乐观锁失败 |
| 4xx | 422 Unprocessable Entity | 语义错误 | 参数格式正确但语义不合法 |
| 4xx | 429 Too Many Requests | 请求过多 | 触发限流 |
| 5xx | 500 Internal Server Error | 服务器内部错误 | 未捕获异常 |
| 5xx | 502 Bad Gateway | 上游错误 | 网关/代理的上游失败 |
| 5xx | 503 Service Unavailable | 服务不可用 | 维护或过载 |
| 5xx | 504 Gateway Timeout | 上游超时 | 网关/代理等待上游超时 |

**【推荐】4xx 错误的响应体中必须包含足够上下文（错误码、message、可重试标记），便于客户端处理。**


## 四、统一响应结构

### 4.1 必须使用统一包装

**【强制】所有 JSON 响应必须包装为统一结构 `{code, message, data}`，禁止直接返回裸数据。**

**反例：**
```json
// 不同接口响应格式不一致
[{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]   // 接口 A
{"success": true, "user": {"id": 1}}                       // 接口 B
{"errcode": 0, "errmsg": "ok", "data": {"id": 1}}         // 接口 C
```

**正例：**
```json
{
  "code": 0,
  "message": "success",
  "data": [
    {"id": 1, "name": "Alice"},
    {"id": 2, "name": "Bob"}
  ]
}
```

### 4.2 code 与 HTTP status 的关系

**【推荐】HTTP 状态码表达"传输层结果"，`body.code` 表达"业务层结果"。**

| HTTP status | body.code | 含义 |
|-------------|-----------|------|
| 200 | 0 | 业务成功 |
| 400 | 4001 | 参数缺失 |
| 400 | 4002 | 参数格式错误 |
| 400 | 4090 | 业务冲突（如库存不足） |
| 401 | 4010 | 未登录 |
| 403 | 4030 | 无权限 |
| 404 | 4040 | 资源不存在 |
| 500 | 5000 | 服务器内部错误 |

`code` 前 3 位对齐 HTTP 状态码便于客户端按类处理；后 2 位为业务细分。

### 4.3 时间戳使用

**【推荐】所有时间字段使用 ISO 8601 字符串（`2026-08-11T10:30:00+08:00`），禁止返回 Unix timestamp（毫秒 / 秒）数字。**


## 五、统一错误响应

### 5.1 错误响应结构

**【强制】错误响应必须包含 trace_id 用于服务端排查。**

**正例：**
```json
{
  "code": 4040,
  "message": "用户不存在",
  "data": null,
  "trace_id": "01J8Z3X4Y5Z6A7B8C9D0E1F2G3"
}
```

### 5.2 错误信息国际化

**【推荐】错误 message 返回稳定字符串（可被前端 i18n 映射的 code），或根据 `Accept-Language` 返回本地化文本。**

**反例：**
```
"message": "用户 ID 1234567890 不存在或已被禁用，请联系管理员处理"
```

**正例：**
```
"code": "user.not_found_or_disabled"
"message": "User not found or disabled"
"params": {"user_id": "1234567890"}
```


## 六、分页

### 6.1 偏移分页（Offset-based）

**【推荐】管理后台类 UI 使用 offset 分页；用户量大、深度翻页时必须切换为 cursor 分页。**

**正例（offset）：**
```
GET /orders?page=1&page_size=20

响应：
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [...],
    "total": 1234,
    "page": 1,
    "page_size": 20
  }
}
```

### 6.2 游标分页（Cursor-based）

**【推荐】无限滚动 / 大数据量场景使用 cursor 分页。**

**正例：**
```
GET /orders?cursor=eyJpZCI6MTIzfQ&limit=20

响应：
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [...],
    "next_cursor": "eyJpZCI6MTQzfQ",
    "has_more": true
  }
}
```

**【强制】游标必须为不透明字符串（Base64 编码的 JSON / UUID），禁止暴露数据库内部 ID。**


## 七、排序与过滤

### 7.1 排序

**【推荐】通过 `sort` 参数指定排序字段，`order` 指定方向（asc / desc）。**

**正例：**
```
GET /orders?sort=created_at&order=desc
GET /orders?sort=-created_at,+amount  # 多字段排序（-降序，+升序）
```

### 7.2 过滤

**【推荐】复杂过滤使用 `filter[field]=value` 语法，支持范围查询（`gte` / `lte`）。**

**正例：**
```
GET /orders?filter[status]=paid&filter[created_at][gte]=2026-01-01
GET /users?age[gte]=18&age[lte]=60
```

### 7.3 字段投影

**【推荐】支持 `fields` 参数让客户端指定返回字段，减少大字段传输开销。**

**正例：**
```
GET /users?fields=id,username,email   # 仅返回这三个字段
GET /users/123?fields=-password_hash  # 排除敏感字段
```


## 八、版本控制

**【推荐】API 版本通过 URL Path（`/api/v1/`）暴露，避免使用 Header 版本控制（不易调试、难以缓存）。**

**正例：**
```
✅ /api/v1/users
✅ /api/v2/users
```

**反例：**
```
❌ /api/users + Accept: application/vnd.myapi.v1+json
❌ /api/users?version=1
```


## 九、幂等性

### 9.1 幂等键（Idempotency-Key）

**【推荐】所有非幂等的 POST 接口（创建订单、扣款）必须支持 `Idempotency-Key` 请求头，24h 内相同 Key 不重复执行。**

**正例：**
```http
POST /api/v1/orders
Idempotency-Key: 01J8Z3X4Y5Z6A7B8C9D0E1F2G3
Content-Type: application/json

{"product_id": 100, "quantity": 1}
```

服务端必须缓存 Key → 响应结果 24h。

### 9.2 自然幂等

**【强制】PUT / DELETE 默认幂等，无需额外 Key；POST 默认非幂等。**


## 十、限流与缓存

### 10.1 限流响应头

**【推荐】限流响应必须包含标准头：**

| Header | 说明 |
|--------|------|
| `X-RateLimit-Limit` | 时间窗口内允许的最大请求数 |
| `X-RateLimit-Remaining` | 剩余请求数 |
| `X-RateLimit-Reset` | 时间窗口重置时间（Unix timestamp） |
| `Retry-After` | 触发限流后建议重试等待秒数（429 响应专用） |

**正例：**
```http
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 73
X-RateLimit-Reset: 1723353600
```

### 10.2 缓存控制

**【推荐】对 GET 资源启用 HTTP 缓存：**
- `Cache-Control: public, max-age=300`（5 分钟）
- `ETag: "abc123"`（资源指纹）
- `Last-Modified: Mon, 11 Aug 2026 10:30:00 GMT`
- `Vary: Authorization`（区分不同用户的缓存）

**反例：**
```
# 不带任何缓存头，每次都打到数据库
HTTP/1.1 200 OK
Content-Type: application/json
{"users": [...]}
```


## 十一、安全

### 11.1 认证

**【强制】所有非公开接口必须通过 Bearer Token / Cookie 认证；禁止仅依赖 IP / Referer 判断身份。**

### 11.2 输入校验

**【强制】所有用户输入（URL 参数 / 请求体 / Header）必须在服务端做长度、类型、范围校验；禁止信任客户端。**

### 11.3 输出脱敏

**【强制】禁止在响应中返回敏感字段（密码哈希、API Key、身份证号、银行卡 CVV）；如必须展示部分信息，使用掩码（`138****1234`）。**

### 11.4 HTTPS

**【强制】所有 API 必须使用 HTTPS；生产环境必须启用 HSTS（`Strict-Transport-Security: max-age=31536000; includeSubDomains`）。**


## 十二、OpenAPI / Swagger 文档

**【强制】所有 API 必须提供 OpenAPI 3.0+ 规范文档；推荐通过 FastAPI / Flask + 插件自动生成。**

**推荐字段：**
- `summary` / `description`：一句话 + 详细描述
- `tags`：接口分组
- `parameters`：所有参数含 schema / example
- `requestBody`：请求体 schema + example
- `responses`：每个状态码的响应 schema + example
- `security`：声明所需认证方式


## 十三、CORS

**【强制】跨域 API 必须正确配置 CORS，禁止生产环境使用 `Access-Control-Allow-Origin: *` 同时启用 `Access-Control-Allow-Credentials: true`。**

**正例：**
```
Access-Control-Allow-Origin: https://app.example.com
Access-Control-Allow-Credentials: true
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
Access-Control-Allow-Headers: Authorization, Content-Type
```


## 十四、健康检查与可观测性

**【推荐】提供以下端点：**

| 端点 | 用途 |
|------|------|
| `GET /health` | 服务存活（k8s livenessProbe） |
| `GET /ready` | 服务就绪（k8s readinessProbe） |
| `GET /metrics` | Prometheus 指标 |
| `GET /version` | 服务版本号 |


## 十五、快速检查清单

- [ ] URL 是否使用复数名词、无动词？
- [ ] HTTP 方法语义是否准确（GET 安全幂等 / POST 非幂等）？
- [ ] 是否使用了精确的 HTTP 状态码（非一律 200）？
- [ ] 响应是否统一为 `{code, message, data}` 结构？
- [ ] 错误响应是否包含 `trace_id`？
- [ ] 时间字段是否为 ISO 8601 字符串？
- [ ] 大数据量接口是否使用 cursor 分页？
- [ ] 是否暴露 OpenAPI 文档？
- [ ] 敏感字段是否在响应中脱敏？
- [ ] 是否启用 HTTPS 与 HSTS？