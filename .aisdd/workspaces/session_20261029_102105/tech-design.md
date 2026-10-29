# 设备管理后端（充电宝设备）技术设计文档

> **模板版本**: v3.0.0（`.qoder/skills/tech-generate/templates/tech-design-template.md`）
> **关联规范**: `.aisdd/rules/mysql_specification.md` | `.aisdd/rules/redis_specification.md` | `.aisdd/rules/mq_specification.md` | `.aisdd/rules/http_restful_specification.md` | `.aisdd/rules/tech_specification.md` | `.aisdd/rules/java_springboot_specification.md` | `.aisdd/rules/java_spring_specification.md` | `.aisdd/rules/java_middleware_specification.md` | `.aisdd/rules/java_logging_specification.md`

---

## 1. 文档信息

| 属性 | 值 |
|------|-----|
| **文档版本** | V1.3.0（V1.1.0 审核校订 + V1.2.0 实现映射回写 + V1.3.0 技术评审回写，见 §2） |
| **关联 PRD** | **缺失** —— 本次输入为需求方口述（2026-10-29 会话），仓库内无 `prd_final.lock.md`，详见 §1.1 |
| **需求规模** | 中型 —— 功能点 6 个，影响模块 4 个 |
| **创建日期** | 2026-10-29 |
| **作者** | AI Agent（需求方逐条裁决后定稿） |
| **关联文档** | [data-model/data-model.md](data-model/data-model.md)（DDL 与索引的唯一事实来源，V1.1.2） · [contracts/api-contract.yaml](contracts/api-contract.yaml)（HTTP 契约，OpenAPI 3.1，v1.2.1） · [implementation-map.md](implementation-map.md)（目标实现规格，19 个 `IM-xxx` 单元，V1.2.0 回写的三项缺口来自该文档） · [review_reports/tech_review_discussion.md](review_reports/tech_review_discussion.md)（V1.3.0 技术评审记录，含未通过结论与解锁条件） |

### 1.1 前置条件偏差声明（必读）

`.aisdd/rules/tech_specification.md` §3.1 规定"严禁在缺少 PRD 的情况下生成技术设计文档"。本次存在偏差，如实记录：

| 项 | 规范要求 | 现状 | 处置 |
|----|---------|------|------|
| PRD 前置 | 必须有 PRD 并建立 FR 映射 | 无 PRD，需求来自会话口述 | 本文档 §3 自建需求编号 `FR-001~FR-006`，来源标注"口述需求"，待 PRD 补齐后重建映射 |
| Constitution | 加载 `.aisdd/Constitution.md` | 该文件不存在 | **不得写"跳过"**（V1.3.0 更正本行旧表述）：`tech-review` Step 0 是强制前置，该缺失被评审判为 P0 阻塞项 B-01，直接后果是本轮**不得产出 `tech_design_final.lock.md`**。项目级约束的核查临时由 `.aisdd/rules/` 26 份成文规范逐条承担（附录 C），属**降级替代**，不等于宪法检查通过 |
| 技术调研报告 | 引入新技术组件时必须有 `research/tech-research-*.md` | 未产出 | 本次引入 Kafka 消费与 Redis 缓存属新组件，**调研报告为待办**（见 §14 R-006）；§4 选型对比依据为仓库规范 + 通用工程结论，非实测数据 |
| 数据版本管理 | 会话目录需 `prd_final.lock.md` | 不存在 | 会话目录名按时间戳生成 |

> ⚠️ 因缺少 PRD，本文档 **§11.1 性能参数**、**§11.4 容量估算** 全部为"假设值 + 公式"，标注了待业务确认项，不得直接当作承诺指标。

### 1.2 交付物清单与缺口

| 交付物 | 状态 |
|--------|------|
| `tech-design.md` | ✅ 本文档 |
| `data-model/data-model.md` | ✅ 已产出 |
| `contracts/api-contract.yaml`（OpenAPI 3.1） | ✅ 已产出（V1.1.0 随审核校订一并交付；v1.2.0 只补 `x-internal-entries` 的锁 key；v1.2.1 按本轮评审把 `503/5030` 的描述由"已熔断"改为"依赖调用超时/不可用（fail-fast）"，**HTTP 面与字段集合未变**）。含 9 个 HTTP 路径（EP-001/002/003/004/005/009/010/011，其中 EP-011 拆为列表/详情/重放/丢弃 4 个操作）；EP-006 的 Kafka 消息结构以 `components.x-kafka-message-contracts` 登记（非 HTTP 入口）；EP-007/008/012/013 以 `components.x-internal-entries` 登记以免遗漏。**已通过 `swagger-cli validate` 与 `@redocly/cli lint --extends=minimal` 双重校验** |
| 本文档 §5.2 / §9.6 与契约的关系 | §5.2 与 §9.6 是**设计口径**（为什么这么定、边界与裁决），契约是**对接产物**（字段与状态码的机器可读形式）。两者冲突时以契约为准并回写本文档；错误码表（§9.6）与契约 `responses` 已逐项对齐 |
| `implementation-map.md`（目标实现规格） | ✅ 已产出（V1.2.0 随实现映射一并交付）。19 个 `IM-xxx` 单元，覆盖 FR-001~006 / EP-001~013 / R1~R15；已过 `node .aisdd/bin/implementation-map-check.js` 门禁（`errors=0`、`warnings=0`） |
| `tech-review` 评审产物 | ✅ 已产出（V1.3.0）：[review_reports/tech_review.json](review_reports/tech_review.json)（机器可读结论）· [review_reports/tech_review_discussion.md](review_reports/tech_review_discussion.md)（四角色报告 + 分歧留痕 + 两轮 React 修正记录）。**结论：不通过，未锁定**（P0 阻塞 B-01 宪法缺失；第 2 轮总分 7.600 < 8.0）。⚠️ 复算时发现技能定义的 6 维权重求和为 **0.90**（非 1.00），故字面公式满分只有 9.000；**归一化口径下第 2 轮为 8.444（已过线）**。本轮未擅自改算法，两种数字都写进产物（评审记录 S-01），且**结论不受影响**——B-01 一票否决独立成立，因此**剩余真实阻塞只有宪法一项** |
| `tech_design_final.lock.md`（设计锁定件） | ❌ **未产出，且本轮不可能产出**。`tech-review` 已于 V1.3.0 执行（2 轮 React 循环），按该技能 Step 0（宪法前置为强制项、且宪法有一票否决权）与 Step 6（总分 ≥ 8.0 才可锁定）两条规则，评审结论为**不通过**，因此不生成锁定件。本文档处于"已评审但未锁定"的 V1.3.0 状态；解锁需要 §14 R-010 列出的四项外部输入，其中 B-01 只能由需求方决定（补宪法或明确豁免并留痕） |
| `research/tech-research-*.md`（技术调研报告） | ❌ 未产出，见 §1.1 与 §14 R-006。本次引入 Kafka 消费、Redis 缓存、MySQL 分区三项能力属新组件，报告仍是欠账 |

---

## 2. 版本修订记录

| 版本号 | 修订日期 | 修订人 | 关联 PRD 版本 | 变更类型 | 变更描述 | 影响范围 |
|-------|---------|-------|-------------|---------|---------|---------|
| V1.0.0 | 2026-10-29 | AI Agent | 无 PRD（口述需求） | 新建 | 初始版本：外部 Kafka 事件驱动的设备信息镜像 + 对外查询服务 | — |
| V1.1.0 | 2026-10-29 | AI Agent | 无 PRD（口述需求） | 修订 | 审核校订：新增 R14（动态列注入面）/R15（优雅停机）；补 Flyway 迁移、Actuator 探针 EP-013、§9.7 日志要求、`trace_id` 规则、限流与缓存响应头、分页参数改 `page_size`、EP-004 删哨兵与入参边界、死信重放幂等冲突 `409/4092`、重试加 jitter 与超时预算；登记 Spring Boot 规范与 HTTP 规范的响应体冲突（附录 C、附录 E #9）；产出 `contracts/api-contract.yaml` | §5.2、§9、§11~§14、附录 C/E |
| V1.2.0 | 2026-10-29 | AI Agent | 无 PRD（口述需求） | 修订（实现映射回写） | 生成 `implementation-map.md`（19 个 `IM-xxx`）时暴露并回写三项设计缺口：**G-001** EP-007/EP-008 定时任务在 ingest 多实例下会重复跑 → §5.2 加 `device:lock:cron:{job}` 互斥要求、新增 `CronLock`；**G-002** §11.1 补 12 个实现必需但未登记的配置键；**G-003** §9.4 补 3 个新 Redis key（任务互斥锁 / 对账水位 / 后台建档幂等键）。另在 §1.2 如实登记 `tech_design_final.lock.md` 与调研报告两项欠账 | §1.1~§1.2、§2、§5.2、§9.4、§11.1 |
| V1.3.0 | 2026-10-29 | AI Agent（`tech-review` 四角色评审） | 无 PRD（口述需求） | 修订（评审回写，**结论仍为不通过**） | 评审发现并修正：① **B-02** 依赖治理——`device-core` 未声明 spring-web/webmvc，而 `IM-CORE-007` 把 `@RestControllerAdvice`/`OncePerRequestFilter` 放在 core，按 V1.2.0 的 POM 无法编译 → core 以 `provided` 作用域引入 `spring-boot-starter-web`（§12.4）；② **B-03** 弹性口径——§9.6 的 `503/5030` 写成"已熔断"但无组件与配置承接，且映射把 `resilience4j` 只装到 `device-ingest`（熔断行为却在读侧 openapi）→ 本期**不引入 Resilience4j**，改为"出站依赖必有超时 + fail-fast"，登记超时键与不变式（§11.1、§9.6）；③ R14 覆盖范围由"动态列名"扩到"动态 SQL 标识符（列名 + 分区名）"（§9.3）；④ 事实性纠正：EP-010 旧表述"分区表按时间倒序天然裁剪"不成立，`WHERE sn=?` 不含分区键即**无分区裁剪**（§9.1）；⑤ 安全：AppKey Secret 与 JWT 公钥的来源、存储与轮换口径登记（§5.2、§12.4）；⑥ G-001 cron 锁补"单次任务时长必须 < 锁 TTL"不变式与超限告警，明确**无 watchdog 续期**（§5.2），并为此新增配置键 `device.cron.lock-safety-margin-ratio`；⑤ 的密钥来源同时落为配置键 `device.openapi.app-secret-path`，**G-002 表由 12 键增至 14 键**；⑦ 新增 §12.4 依赖登记与不引入清单、§13 TC-OPS-02/03、TC-SEC-02/03，§14 R-010，附录 E #12~#14（含需求方"DDL 不需要迁移"与本仓库【强制】条款的冲突裁决）。**评审第 2 轮总分 7.6 < 8.0，且 B-01 宪法前置未通过 → 不产出锁定件** | §1.1~§1.2、§2、§5.2、§9.1、§9.3、§9.6、§11.1、§12、§13、§14、附录 C/E |
| V1.3.1 | 2026-10-29 | AI Agent（编码启动回写） | 无 PRD（口述需求） | 修订（工具链与 DDL 交付形态定稿；**评审状态不变，仍为未通过/未锁定**） | 需求方指示"实现代码：maven + jdk21 + springboot"，按"先定稿文档再动代码"的既有流程回写两处：<br>① **工具链具体化**：Java **21**（Temurin 21.0.11）+ Maven 3.9.16 + Spring Boot **3.5.16**，非 Boot 托管依赖单点钉版（MyBatis starter 3.0.5 / springdoc 2.8.17 / Testcontainers 1.21.4）。原表述"Java 17 / Boot 3.2+"是本仓库规范的**基线**，本版取高于基线的具体版本，属需求方指定而非偏离。<br>② **附录 E #12 关闭**：证明"不产出 `.sql`"与"弃用 Flyway 人工执行"两种读法对代码的处置**完全一致**（都不引 `flyway-core`、都不落 `db/migration/*.sql`），故编码不必先等该裁决；附录 C 的 Spring Boot §迁移【强制】由"⚠️ 未闭环"改判为"**❌ 有意偏离 + 豁免留痕**"并写出偏离代价与两条补偿措施；§12.1 的"迁移脚本已存在"歧义消除。<br>③ 新增集成测试夹具 `device-core/src/test/resources/db/test-schema.sql`（逐字取自 `data-model` §6，**仅测试用**，不构成生产迁移产物）——这是"禁 H2"与"无 Flyway"两条硬约束同时成立时的唯一可行落点。<br>④ `implementation-map` 同步：IM-BUILD-001 的根 POM 行与"版本管理"约束行、IM-DB-001 全单元（4 个 Code targets + 4 个约束行 + sketch + 控制流 + Tests 首行）、单元索引表 IM-DB-001 行、B-04 处置行。<br>**未改动**：§1.2 评审结论、7.6 分与 B-01 宪法前置（评审未通过不构成编码阻塞，由需求方本次指示明确覆盖） | §2、§12.1、附录 C（Spring Boot §迁移行）、附录 E #10/#12；`implementation-map` IM-BUILD-001 / IM-DB-001 |

---

## 3. 需求映射

需求定位（口述原文要点）：只做设备信息的增删改查；设备在线状态、出厂注册、是否绑定、是否回收/报废**全部来自外部系统并经 Kafka 通知**；接收侧需做**幂等与乐观锁**；对外提供**独立的设备信息查询 API 服务**，用 **Redis 做设备信息缓存**。

| 需求编号 | 需求描述 | 优先级 | 来源 | 技术落地方式 |
|---------|---------|-------|------|------------|
| FR-001 | 接收外部设备变更通知并准确落地（出厂注册/在线/绑定/回收报废四类维度） | P0 | 口述 2026-10-29 | `device.info.change` 单 Topic + `msg_type` 分派；同事务写幂等表 + 主档分维度 CAS 更新 |
| FR-002 | 消费幂等，重复投递不产生重复副作用 | P0 | 口述 | Redis `SETNX` 处理中键（廉价挡并发）+ `mq_consume_record.uk_source_msg` 唯一索引（正确性硬保证） |
| FR-003 | 并发写同一设备时不产生丢失更新 | P0 | 口述 | `device_info.version` 乐观锁，`UPDATE ... WHERE sn=? AND version=?` 影响行数判定 |
| FR-004 | 对外提供独立的设备信息查询服务 | P0 | 口述 | `device-openapi` 进程：`GET /api/v1/devices/{sn}`、`POST /api/v1/devices/batch` |
| FR-005 | 设备信息走 Redis 缓存 | P0 | 口述 | Cache-Aside，`device:cache:info:{sn}`，先写库后删缓存 + TTL 抖动 + 定时对账 |
| FR-006 | 设备信息后台增删改查与变更留痕 | P1 | 口述 | `device-admin` 进程：分页列表、新增、修改（乐观锁，冲突不静默重试）、逻辑删、变更流水查询、死信重放 |

**明确不做的范围**（需求方裁决，写进边界以防蔓延）：生命周期流转规则与状态机校验、安装/撤回/返厂/报废单据与审批、点位与槽位、借还流转、固件版本差异与 OTA、MQTT/设备直连接入层、对外事件二次发布、分库分表、微服务与网关、K8s、分布式事务、复杂报表。
→ "是否回收/报废"在本系统中**只是一个由外部推送的状态列**，不是流程。

---

## 4. 技术方案选型对比 ⭐

### 4.1 技术决策点识别

| 决策点ID | 决策点描述 | 影响范围 | 紧迫程度 |
|---------|----------|---------|---------|
| TD-001 | 外部变更的接收形态：HTTP 同步接收 vs Kafka 异步消费 | device-ingest | 高 |
| TD-002 | 消费幂等实现：仅 Redis / 仅 DB 唯一索引 / 双层 | device-ingest | 高 |
| TD-003 | 并发写控制：悲观锁 / 乐观锁 / 无控制 | device-core | 高 |
| TD-004 | 乱序（旧事件后到）防护方案 | device-ingest | 高 |
| TD-005 | 缓存与 DB 一致性策略 | device-openapi | 高 |
| TD-006 | 服务进程形态：单进程 vs 三进程分离 | 全局部署 | 中 |

### 4.2 TD-001：外部变更的接收形态

> 🎯 **背景**：需求方明确"设备状态信息由外部通过 Kafka 通知"（FR-001）。

#### 方案对比

| 维度 | 方案 A：HTTP 同步接收落库 | 方案 B：Kafka 异步消费落库 | 方案 C：Kafka + 本地消息表二次投递 |
|------|------------------------|--------------------------|--------------------------------|
| 技术成熟度 | ★★★★★ | ★★★★★ | ★★★★☆ |
| 削峰能力 | 无，洪峰直接打到 DB | 强，Broker 缓冲 | 强 |
| 可回溯/重放 | 无（请求即焚） | 有（offset 重放 + 死信表） | 有 |
| 失败补偿 | 调用方自行重试 | 服务端重试 + 死信 | 服务端重试 + 死信 |
| 运维复杂度 | 低 | 中（需 Broker 与监控） | 高（多一套消息表与调度） |
| 与现有架构兼容性 | 高 | 高（仓库已有 `mq_specification.md` 全套规范） | 低（本项目不发消息，无发送记录表需求） |
| 适用场景 | 低频、强实时 | 高频、可容忍秒级延迟 | 收发双侧均需补偿 |

#### 优劣分析

**方案 A：HTTP 同步接收**
- ✅ 优势：链路短、无中间件依赖、实时性最好。
- ⚠️ 劣势/风险：外部批量刷数时写放大直接落到 DB；外部故障重试全靠对方实现；无重放能力，一条失败即丢失。
- 📦 典型使用场景：低频人工触发。

**方案 B：Kafka 异步消费（推荐）**
- ✅ 优势：削峰、可回溯、天然支持重试与死信；符合需求方给定的输入形态；仓库 `mq_specification.md` 已有幂等表/死信表/Topic 命名的成文标准，落地即合规。
- ⚠️ 劣势/风险：引入 Broker 运维；端到端延迟由批处理与拉取间隔决定（秒级）；必须正确处理重复与积压。
- 📦 典型使用场景：外部系统批量推送状态变更。

**方案 C：Kafka + 本地消息表**
- ✅ 优势：DB 与消息强一致。
- ⚠️ 劣势/风险：`mq_specification.md` §5.1 的该方案针对**生产端**"DB 与消息强一致"；本项目是纯消费方、不对外发消息，属无效复杂度。

#### 适用性分析（结合本项目约束）

| 约束因素 | 说明 | 影响哪个方案 |
|---------|------|------------|
| 需求方给定输入形态 | 外部经 Kafka 通知 | 排除 A 作为主链路 |
| 是否对外发消息 | 否（已裁决不做二次发布） | 排除 C |
| 团队技术栈 | Java 后端实训/演示为主，中间件件数需可控 | B 可接受（仅 Kafka 一项新增） |

#### 采用建议

> **推荐方案：方案 B（Kafka 异步消费落库）**
>
> 理由：与需求方给定的数据形态一致，天然获得削峰与重放能力，且仓库已有成文 MQ 规范可直接对齐。不选 A 是因为外部刷数时同步写会打穿 DB、且失败无法补；不选 C 是因为本项目无生产侧消息，本地消息表是空转复杂度。
>
> ⚠️ 注意事项：Topic 与消息体必须按 `mq_specification.md` §1.2/§1.3 命名与字段约束，详见 §5.2 EP-006 与附录 A。
>
> 📋 本决策将生成 ADR-1，归档至知识库。

### 4.3 TD-002：消费幂等实现

> 🎯 **背景**：Kafka 提供 At-Least-One 语义，重复投递是常态而非异常（FR-002）。

#### 方案对比

| 维度 | 方案 A：仅 Redis `SETNX` | 方案 B：仅 DB 唯一索引 | 方案 C：Redis + DB 双层（推荐） |
|------|------------------------|---------------------|------------------------------|
| 挡重复可靠性 | ❌ Redis 故障/Key 过期即失效 | ✅ 硬保证 | ✅ 硬保证 |
| 重复消息处理成本 | 极低（内存判定） | 需先插入触发冲突异常 | 极低（多数在 Redis 层挡掉） |
| 与事务回滚的耦合风险 | 高（见 §9.3 坑 1） | 无 | 中，需显式释放 |
| 运维依赖 | Redis 可用性 | 无额外依赖 | 两者 |
| 规范符合度 | 不满足"唯一索引防重的硬保证" | 满足 `mq_specification.md` §4.4【强制】 | 满足且额外获得吞吐收益 |

#### 优劣分析

**方案 A：仅 Redis**
- ✅ 优势：无 DB 写放大。
- ⚠️ 劣势/风险：**Redis 不是持久化事实来源**——Key 过期、Redis 主从切换丢数据、`flushdb` 事故，任何一种都会让重复消息直接落到业务写。幂等不能建立在可丢失的存储上。

**方案 B：仅 DB 唯一索引**
- ✅ 优势：正确性无懈可击，实现最简。
- ⚠️ 劣势/风险：每条重复消息都要付一次"插入→唯一键冲突异常→回滚"的代价；重复率高（如外部全量重推）时异常开销与主从延迟明显。

**方案 C：Redis 挡并发 + DB 兜底（推荐）**
- ✅ 优势：绝大多数重复在 Redis 层零成本挡掉；Redis 失效时 DB 唯一索引仍然拦住，正确性不因缓存降级而失守。
- ⚠️ 劣势/风险：必须处理"Redis 键置位后事务回滚"的窗口，否则重试消息被误判重复而丢失（§9.3 坑 1，已给出释放策略）。

#### 适用性分析

| 约束因素 | 说明 | 影响哪个方案 |
|---------|------|------------|
| 仓库规范 | `mq_specification.md` §3.1：幂等键优先"业务唯一键 + 唯一索引 / Redis SetNX"，§4.4：幂等字段唯一索引【强制】 | A 单独使用不合规，C 合规 |
| 数据正确性优先级 | 状态数据被外部反复刷，宁可多算不能漏算 | 排除 A |

#### 采用建议

> **推荐方案：方案 C（Redis 处理中键 + `mq_consume_record` 唯一索引双层）**
>
> 理由：DB 唯一索引是唯一可信的幂等依据，Redis 只是吞吐优化与并发挡，二者职责必须这样排布；不选 A 是把正确性押在可丢失存储上，不选 B 是让重复消息付出异常回滚代价。
>
> ⚠️ 注意事项：Redis 键语义是"正在处理"而非"已处理"，事务回滚路径必须释放该键（见 §9.3）。
>
> 📋 本决策将生成 ADR-2，归档至知识库。

### 4.4 TD-003：并发写控制

> 🎯 **背景**：同一 `sn` 会被 Kafka 消费者与后台运营同时写入；后台写会更新 `maintain_user`，与外部维度写落在同一行（FR-003）。

#### 方案对比

| 维度 | 方案 A：悲观锁 `SELECT ... FOR UPDATE` | 方案 B：乐观锁 `version` CAS（推荐） | 方案 C：不加控制，直接覆盖 |
|------|-----------------------------------|----------------------------------|-----------------------|
| 丢失更新防护 | ✅ | ✅ | ❌ 存在写覆盖 |
| 持锁时长 | 事务全程，含业务计算 | 无锁，仅提交时校验 | — |
| 死锁风险 | 有（多行加锁顺序） | 无 | 无 |
| 冲突代价 | 后者排队等待 | 后者重试或提示 | 静默丢数据 |
| 吞吐（本项目冲突概率低） | 够用 | 更优 | 最高但不可用 |
| 可测试性 | 难构造等待断言 | 易（影响行数=0 可单测） | — |
| 规范符合度 | 允许 | 允许 | 违反 `mysql_specification.md` §1.3 |

#### 优劣分析

**方案 A：悲观锁**
- ✅ 优势：冲突高时吞吐更稳，写语义直观。
- ⚠️ 劣势/风险：锁持有时间等于事务时长，消费端与后台在同一行上排队；跨行更新（批量）需严格加锁顺序否则死锁；连接被长事务占用。

**方案 B：乐观锁 CAS（推荐）**
- ✅ 优势：无锁窗口，写冲突转化为可观测指标；"影响行数 = 0"是可单测、可集成验证的确定性行为。
- ⚠️ 劣势/风险：重试必须有上限并区分写入方（消费者重试、后台提示刷新，见 §9.3 规则 4）；高频冲突场景下吞吐退化——本项目按维度分列更新，冲突集中在"后台编辑 + 外部同期推送"，概率低。

**方案 C：无控制覆盖写**
- ⚠️ 致命：两个来源各写不同列时，全字段 update 会把对方刚写的值抹回旧值，且无留痕。

#### 适用性分析

| 约束因素 | 说明 | 影响哪个方案 |
|---------|------|------------|
| 冲突频率 | 外部推送与后台编辑同 SN 的概率低 | 倾向 B |
| 等待方 | 消费者无人等待，后台有人等待 | B 配合差异化重试策略刚好 |
| 可讲解性（实训定位） | 需要能复现"两个人同时改为什么不乱" | B 更直观 |

#### 采用建议

> **推荐方案：方案 B（`version` 乐观锁 + 分维度动态列更新）**
>
> 理由：冲突概率低、无锁持有、正确性判据明确（影响行数）。不选 A 是因为它会让人为低概率冲突付出全局排队与死锁治理成本；C 直接排除。
>
> ⚠️ 注意事项：乐观锁必须与"分维度只更新本维度列"配合使用，否则单靠 `version` 只是把覆盖变成了重试风暴。
>
> 📋 本决策将生成 ADR-3，归档至知识库。

### 4.5 TD-004：乱序（旧事件后到）防护 —— 本方案最大的能力缺口

> 🎯 **背景**：需求方确认**外部消息体不提供业务事件时间，只有 Kafka 写入时间**；而 `mq_specification.md` §1.3【强制】要求消息体必须含 `event_time`。规范与现实冲突，必须显式裁决。

#### 方案对比

| 维度 | 方案 A：业务事件时间水位 | 方案 B：Kafka 写入时间水位 | 方案 C：维度单一权威源 + 幂等 + 乐观锁 + 人工纠正（推荐） |
|------|----------------------|------------------------|--------------------------------------------------|
| 防"旧事件后到覆盖新值" | ✅ 唯一真正有效的方案 | ❌ **无效**，见下方分析 | ❌ 不防（明确放弃） |
| 防跨源互相覆盖 | 部分（同维度多源仍需仲裁） | 部分 | ✅ 有效 |
| 实现成本 | 低（一列比较） | 低 | 低（配置表 + 校验） |
| 当前可行性 | ❌ **外部契约不支持** | 可行但无价值 | ✅ 可行 |

**为什么方案 B 必须否决**：分区键为 `sn`，同一设备的消息全落在**同一个分区**，而 Kafka 的 LogAppendTime **在分区内单调递增**。用分区内单调递增的序列去判断"这条是不是旧的"，判定结果恒为"新"，防线为空。它唯一能挡的"人为回溯 offset 重放历史消息"场景，又已被 TD-002 的幂等唯一索引完全覆盖（同一 `msg_id` 命中唯一键）。**结论：该水位字段对正确性零贡献，不实现。**

#### 采用建议

> **推荐方案：方案 C，并对外提出契约诉求**
>
> 理由：在没有业务事件时间的前提下，任何"时间戳比较"都退化为无效动作；能确定拿到的只剩"谁能写哪个维度"这一所有权约束。因此把正确性收敛到**不丢（同事务 + At-Least-One + 死信）、不重（唯一索引）、不错位覆盖（维度权威源 + 乐观锁）**三条，乱序明确放弃、以变更流水发现 + 后台人工纠正兜底。不选 A 是外部契约不支持；不选 B 是上面论证的"零贡献"。
>
> ⚠️ 注意事项：
> 1. 本决策使系统**不满足** `mq_specification.md` §1.3 关于消息体必须含 `event_time` 的强制项，属外部生产者缺口，需由需求方向外部系统提出契约补齐（§14 R-001）。
> 2. 契约补齐后，只需在 `DeviceInfoApplier` 增加一处 `event_time` 水位比较即可启用方案 A，DDL 已预留维度时间列（`produce_time / bind_time / scrap_time`）可作水位基准，**不需要改表结构**。
> 3. 该缺口必须写进对外接口文档，不得表述为"支持乱序防护"。
>
> 📋 本决策将生成 ADR-4，并在知识库标注为"已知能力缺口"。

### 4.6 TD-005：缓存与数据库一致性

> 🎯 **背景**：FR-005 要求对外查询走 Redis 缓存，缓存值来自 Kafka 消费后的主档。

#### 方案对比

| 维度 | 方案 A：先删缓存后写库 | 方案 B：先写库后删缓存 + TTL + 定时对账（推荐） | 方案 C：延迟双删 | 方案 D：订阅 binlog（Canal）异步删缓存 |
|------|-------------------|------------------------------------------|--------------|--------------------------------|
| 脏数据窗口 | ❌ 大（并发读可回填旧值） | 小（≤ 对账周期） | 较小 | 小 |
| 规范符合度 | ❌ 违反 `redis_specification.md` §2.2【强制】 | ✅ | ✅（§2.2【推荐】） | ✅ |
| 新增组件 | 无 | 无（一个定时任务） | 需延迟队列/调度 | ❌ 需 Canal + 链路 |
| 实现与运维复杂度 | 低 | 低 | 中 | 高 |
| 极端场景兜底 | 无 | TTL 自然过期 + 对账 | 第二次删除失败仍漏 | 依赖 Canal 存活 |

#### 优劣分析

**方案 B：先写库后删缓存（推荐）**
- ✅ 优势：`redis_specification.md` §2.2 正例即此顺序；**删除而非更新缓存**规避并发写互相覆盖旧值；三层兜底职责清晰。
- ⚠️ 劣势：删缓存失败时，TTL 内持续返回旧值；由 `update_time` 增量对账收敛。

**方案 C：延迟双删**
- ✅ 优势：进一步压缩"读请求把旧值装回缓存"的窗口。
- ⚠️ 劣势：第二次删除依赖延迟调度，进程重启即丢失；本项目已有"提交后删 + 对账"，双删收益边际、代价是新增调度设施。列为 P2 可选增强。

**方案 D：binlog 订阅**
- ⚠️ 劣势：新增 Canal 组件与运维面，与"中间件件数可控"约束冲突，排除。

#### 采用建议

> **推荐方案：方案 B。** 一致性口径统一为三句话——**主动删除负责快、TTL 兜底负责错、定时对账负责补**（详见 §9.4）。不选 A 是仓库规范明令禁止；不默认 C/D 是收益抵不上设施成本。
>
> ⚠️ 注意事项：对外必须声明"最终一致，脏读窗口 ≤ 对账周期"，不得宣称强一致。
>
> 📋 本决策将生成 ADR-5，归档至知识库。

### 4.7 TD-006：服务进程形态

#### 方案对比

| 维度 | 方案 A：单一进程全功能 | 方案 B：三进程（ingest / openapi / admin）（推荐） | 方案 C：微服务化（含网关/注册中心） |
|------|------------------|---------------------------------------------|---------------------------|
| 故障隔离 | ❌ 对外查询被打挂 → 消费停摆 → 数据不落 | ✅ 对外流量与数据落地互不影响 | ✅ |
| 独立限流与扩容 | ❌ 只能整体扩 | ✅ 读侧按 QPS 扩、写侧按 lag 扩 | ✅ |
| 部署与运维复杂度 | 最低 | 低（同构建产物，不同启动模块与 profile） | ❌ 高 |
| 代码复用 | 天然 | 通过 `device-core` 共享领域包 | 需契约与版本管理 |
| 与需求约束匹配 | 边界不清 | 恰好承载"读写分离"讲解 | ❌ 需求方已排除微服务 |

#### 采用建议

> **推荐方案：方案 B** —— Maven 多模块**模块化单体**，三个可执行启动模块共用 `device-core`。
>
> 理由：对外查询面向不可控流量，与"数据必须落地"的写侧必须故障隔离并可各自扩缩。不选 A 是一个刷量方即可让状态落地停摆；不选 C 是需求方明确排除的过度设计。
>
> ⚠️ 注意事项：三进程**共用同一 MySQL 与同一 Redis 实例**，写侧删缓存直接命中同一 key 空间，无需跨进程通知；实体与 Mapper 只能定义在 `device-core`，禁止 openapi/admin 各自建模。
>
> 📋 本决策将生成 ADR-6，归档至知识库。

---

## 5. 系统入口清单 ⭐

### 5.1 入口总览表

| 入口ID | 入口类型 | 入口名称 | 触发条件 | 关联需求 | 优先级 |
|--------|----------|----------|----------|---------|--------|
| EP-001 | HTTP API | 查询单个设备信息 | 外部调用方按 SN 查询 | FR-004, FR-005 | P0 |
| EP-002 | HTTP API | 批量查询设备信息 | 外部调用方提交 SN 列表 | FR-004, FR-005 | P0 |
| EP-003 | HTTP API | 设备分页列表（后台） | 运营人员筛选 | FR-006 | P1 |
| EP-004 | HTTP API | 新增设备（后台） | 运营人员录入 | FR-006 | P1 |
| EP-005 | HTTP API | 修改设备信息（后台） | 运营人员编辑，携带 `version` | FR-003, FR-006 | P1 |
| EP-006 | **MQ Consumer** | 设备变更消息消费 | `device.info.change` 有新消息 | FR-001, FR-002, FR-003, FR-005 | P0 |
| EP-007 | **定时任务** | 缓存与 DB 增量对账 | 周期触发 | FR-005 | P2 |
| EP-008 | **定时任务** | 幂等表与流水归档清理 | 周期触发 | NFR-002 | P2 |
| EP-009 | HTTP API | 逻辑删除设备（后台） | 运营人员删除 | FR-006 | P1 |
| EP-010 | HTTP API | 查询设备变更流水（后台） | 运营人员排查 | FR-006 | P1 |
| EP-011 | HTTP API | 死信查询与重放（后台） | 运维处理失败消息 | FR-001 | P2 |
| EP-012 | **隐形入口** | 维度权威源配置加载 | 进程启动 + 周期刷新 | FR-001 | P0 |
| EP-013 | **隐形入口** | 运行探针与健康检查（`/actuator/health/liveness`、`/readiness`、`/metrics`） | 探针/监控系统周期拉取 | NFR-004 | P0 |

> EP-008 / EP-012 / EP-013 属易被漏设的隐形入口：前者决定数据留存与查询正确性，中者是 TD-004 防线的实际执行点，后者是仓库规范的【强制】项（Actuator 探针 + liveness 不含外部依赖）。
>
> **NFR 编号说明**（无 PRD，本文档自拟）：NFR-001 = 对外查询性能与可用性；NFR-002 = 数据留存与归档；NFR-003 = 安全（鉴权、限流、字段可见性）；NFR-004 = 可观测性。

### 5.2 各入口详细定义

> 本节是**设计口径**（每个入口的边界、裁决与约束的"为什么"）。机器可读的字段与状态码已导出为 [contracts/api-contract.yaml](contracts/api-contract.yaml)（OpenAPI 3.1.0，9 个路径 / 11 个操作 + Kafka 消息契约 + 隐形入口登记）。两处冲突以契约为准并回写本节，由 §13 TC-API-03 拦截漂移。

#### EP-001：查询单个设备信息（HTTP API）

| 属性 | 值 |
|------|-----|
| 入口类型 | HTTP RESTful API |
| 所属进程 | `device-openapi` |
| 请求方法 / 路径 | `GET /api/v1/devices/{sn}` |
| 认证方式 | `X-App-Key` + `X-Timestamp` + `X-Signature`（HMAC-SHA256，时间窗 ±`${device.openapi.sign-window-seconds:300}` 防重放）。签名串 `method + "\n" + path + "\n" + timestamp + "\n" + bodySha256`，比较用常数时间（`MessageDigest.isEqual`，禁止 `String.equals`）。**Secret 的来源、存储与轮换见 §12.4**——只允许环境变量或挂载文件，禁止进 `application.yaml`、日志与响应 |
| 幂等性 | 支持（只读） |
| 限流 | 按 AppKey 配额，超限 `429` + `code=4290` + `Retry-After`；所有响应恒带 `X-RateLimit-Limit/Remaining/Reset` 与 `Cache-Control: no-store`（§9.6） |
| 关联需求 | FR-004, FR-005 |

**响应字段白名单（与缓存 Value 一致，见 §9.4/§9.5）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `sn` | string | 设备序列号 |
| `model_code` / `model_name` | string | 型号编码 / 名称 |
| `factory_code` / `factory_name` | string | 工厂编码 / 名称 |
| `produce_time` | string(ISO 8601) | 生产时间；未同步返回 `null` |
| `register_status` | int | 0-未注册 / 1-已注册 |
| `online_status` | int | 0-未知 / 1-在线 / 2-离线 |
| `bind_status` / `bind_target` / `bind_time` | int / string / string | 绑定状态、绑定目标、绑定时间 |
| `recover_status` / `scrap_time` | int / string | 0-正常 / 1-已回收 / 2-已报废；报废时间 |
| `remark` | string | 备注 |
| `image_url` | string | 设备图片 URL（公网可读，无需签名） |

**响应示例**

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "sn": "PB2026102900001",
    "model_code": "PB-10K-2026A",
    "model_name": "20000mAh 双口快充",
    "factory_code": "F-DG-01",
    "factory_name": "东莞一厂",
    "produce_time": "2026-08-11T10:30:00+08:00",
    "register_status": 1,
    "online_status": 1,
    "bind_status": 1,
    "bind_target": "STORE-8823",
    "bind_time": "2026-09-02T14:05:00+08:00",
    "recover_status": 0,
    "scrap_time": null,
    "remark": "",
    "image_url": "https://cdn.example.com/device/pb-10k-2026a.png"
  },
  "trace_id": "01J8Z3X4Y5Z6A7B8C9D0E1F2G3"
}
```

**错误响应示例（`http_restful_specification.md` §5.1【强制】含 `trace_id`）**

```json
{ "code": 4040, "message": "device not found", "data": null, "trace_id": "c0a8ab121729..." }
```

> **不返回**：`id`、`version`、`deleted`、`create_time`、`update_time`、`maintain_user`、`maintain_time`、`ext_json`（需求方裁决见 §9.5）。

**数据流概要**

```
[GET /api/v1/devices/{sn}]
    ▼ [SignatureAuthInterceptor]  验签 + 时间窗 + AppKey 配额
    ▼ [DeviceInfoQueryService]    ① GET device:cache:info:{sn}
    │                            ② 命中哨兵 __EMPTY__ → 直接 404（穿透防护，不回源）
    │                            ③ miss → SETNX device:lock:cache:rebuild:{sn}
    │                                 抢到 → 回源 DB → 回填（TTL + 抖动）
    │                                 未抢到 → 自旋读缓存，超时降级直查 DB 且不回填
    ▼ [DeviceInfoPublicAssembler] 白名单裁剪 + 哨兵时间 → null
    ▼ [HTTP 200 {code,message,data,trace_id} + Cache-Control: no-store + X-Trace-Id]
```

#### EP-002：批量查询设备信息（HTTP API）

| 属性 | 值 |
|------|-----|
| 所属进程 | `device-openapi` |
| 方法 / 路径 | `POST /api/v1/devices/batch` |
| 请求体 | `{ "sns": ["...", "..."] }`，单次上限 `${device.query.batch-max:100}`（待确认；超限 `400/4003`） |
| 响应 | `{code, message, data:{ items:[{sn, found, info}], missing:[sn] }, trace_id}` |
| 方法语义裁决 | 用 `POST` 承载只读批量查询：单次 100 个 SN 无法放进 GET 的 URL（长度与缓存代理限制），这是 HTTP 规范 §2.1"GET 安全幂等"的**有意偏离**。代价与约束：① 不得有副作用（服务端只读，实现上禁止走写路径）；② **响应仍按幂等处理**，同 body 重放结果一致；③ 禁止用它绕过限流（同样计入 AppKey 配额） |
| `missing` 与 `items` 的口径 | `missing` 只是 `items` 中 `found=false` 的 SN 摘要，**由同一次装配产出，不是第二份判定结果**。实现若分两处计算即为缺陷（同一事实两处口径） |
| 实现约束 | 一次 `MGET`（禁循环 `GET`，Redis 规范 §1.4【强制】）；miss 部分一次 `WHERE sn IN (...) AND deleted=0`（禁 N+1）；回填用 `Pipeline` |
| 边界 | 只走缓存与主档，**不做筛选、不排序、不分页** |

#### EP-003 ~ EP-005、EP-009 ~ EP-011：后台 HTTP API

| 入口 | 方法 / 路径 | 关键约束 |
|------|------------|---------|
| EP-003 | `GET /api/v1/admin/devices?page=&page_size=&model_code=&sn_prefix=&online_status=&produce_time_start=&produce_time_end=` | Offset 分页，参数与响应体按 HTTP 规范 §6.1 正例：`{items,total,page,page_size}`；**必须至少带一个高区分度筛选条件**（`sn` / `sn_prefix` 右模糊 / `model_code` + 时间范围），禁止无条件全量翻页；深翻页上限 `${device.admin.max-offset:10000}`，超出 → `400/4002`；**列表项含 `version`**（后台乐观锁需要，与对外白名单不同——见 §9.5 后台可见列） |
| EP-004 | `POST /api/v1/admin/devices` | 需 `Idempotency-Key` 头（HTTP 规范 §9.1）；`uk_sn` 冲突 → `409/4090`；成功 `201`。**请求体只接受 `sn` + PROFILE 列**（`model_code/model_name/remark/image_url`）；状态列与时间列**不接受入参**，服务端固定 `register_status=0, online_status=0, bind_status=0, recover_status=0, deleted=0, version=0`，四个时间列写哨兵；`maintain_user/time` 由服务端写；成功后 `DEL device:cache:info:{sn}` 以清掉可能存在的 `__EMPTY__` 哨兵（§11.2 缺陷 9） |
| EP-005 | `PUT /api/v1/admin/devices/{sn}` | 请求体 = PROFILE 列 + `version`（全量替换 PROFILE 维度，其余维度不受影响）；`sn` 不存在或已逻辑删 → `404/4040`；影响行数 0 → `409/4091`"数据已被外部更新，请刷新后重试"，**不静默重试**；成功返回 `data:{version}`（新版本，供连续编辑） |
| EP-009 | `DELETE /api/v1/admin/devices/{sn}` | 逻辑删 `deleted=1` + CAS + 删缓存；成功 `204` **无响应体**（§9.6 例外说明）；不存在 → `404/4040`；版本冲突 → `409/4091`；重复删除（已 `deleted=1`）→ `404/4040`，幂等语义由 `version` 保证而非静默 204 |
| EP-010 | `GET /api/v1/admin/devices/{sn}/change-records?page=&page_size=` | 读流水表，`ORDER BY create_time DESC`，走 `idx_sn_create_time`；设备不存在 → `404/4040`；存在但无记录 → `200` + 空 `items`；返回 `items[]{create_time,source,msg_id,msg_type,dim_type,before_json,after_json,result,op_user}` |
| EP-011 | `GET /api/v1/admin/mq/dead-letters?status=0&page=&page_size=` · `POST /api/v1/admin/mq/dead-letters/{id}/replay` · `POST /api/v1/admin/mq/dead-letters/{id}/discard` | MQ 规范 §3.3【强制】死信可查询、可重放、有管理入口；重放复用同一 `DeviceInfoApplier`（**因此受同一套幂等与授权约束**：若原 `msg_id` 已被成功消费过，重放会被唯一索引挡下并返回 `409/4092`）；`{id}` 不存在 → `404/4040`；已重放/已丢弃的记录再次重放 → `409/4093`；两个动作都写 `handle_user/handle_time`；列表返回 `items[]{id,msg_id,source,topic,consumer_group,partition_no,offset_no,msg_key,fail_reason,retry_count,status,handle_user,handle_time,create_time}`，`content` **只在详情 `GET /api/v1/admin/mq/dead-letters/{id}` 返回**（原始消息体可达数百 KB，列表全带会打爆响应包） |
| EP-013 | `GET /actuator/health/liveness` · `GET /actuator/health/readiness` · `GET /actuator/metrics` | Spring Boot 规范【强制】：独立 management 端口、端点显式白名单、除 health/info 外鉴权；**liveness 不含 DB/Redis/Kafka 检查**（否则依赖抖动会杀进程造成雪崩），readiness 才检查依赖 |

> 后台认证：`Authorization: Bearer <JWT>`；操作人账号（拼音）解析后写入 `maintain_user`。JWT 校验口径（V1.3.0 补，评审 B-03/安全项）：**只接受 `RS256`**（禁止 `none`，禁止从 token 头的 `alg` 反推算法——这是算法混淆攻击的入口），`iss`/`aud`/`exp` 三项都必须校验（键 `device.admin.jwt.issuer` / `device.admin.jwt.audience`，公钥只从 `device.admin.jwt.public-key-path` 挂载文件读取，见 §12.4）。EP-011 的死信重放/丢弃与 EP-004/005/009 的写操作都必须落在**已认证主体**上，未授权时返回 `403/4030`；本期不做细粒度角色矩阵（需求未给），该缺口登记为附录 E #13。

#### EP-006：设备变更消息消费（MQ Consumer）

| 属性 | 值 |
|------|-----|
| 入口类型 | Kafka Consumer |
| Topic | `device.info.change`（`业务域.模块.事件类型`，MQ 规范 §1.2） |
| 死信 | Topic `device.info.change.dlq` + 表 `mq_dead_letter` |
| 消费者组 | `device-management-ingest` |
| 分区键 | `sn`（同源同设备串行） |
| 并发数 | `${device.ingest.concurrency}`（建议 3，须 ≤ 分区数） |
| offset 提交 | **手动，仅事务提交成功 / 判定重复 / 死信已落库后提交** |
| 重试 | 乐观锁冲突：进程内指数退避，上限 `${device.ingest.max-retry}`（建议 3）→ 超限落死信 |
| 消息体大小 | 【强制】< 256KB；超限消息直接落死信 |
| 关联需求 | FR-001, FR-002, FR-003, FR-005 |

**消息信封（MQ 规范 §1.3 强制字段 + 本项目扩展 `source`/`msg_type`）**

```json
{
  "msg_id": "ext-20261029-000001",
  "source": "ASSET_SYS",
  "biz_id": "PB2026102900001",
  "event_time": null,
  "msg_type": "ONLINE_CHANGE",
  "data": { "online_status": 1 }
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `msg_id` | ✅ | 幂等键，与 `source` 组合作唯一约束 |
| `source` | ✅ | 外部来源编码，用于维度所有权校验与幂等隔离 |
| `biz_id` | ✅ | 设备 `sn` |
| `event_time` | ⚠️ 规范要求必填，**外部当前不提供** | 缺失置 `null`，本系统不做乱序判定（TD-004，风险 R-001） |
| `msg_type` | ✅ | `FACTORY_REGISTER`/`ONLINE_CHANGE`/`BIND_CHANGE`/`RECOVER_CHANGE`/`PROFILE_CHANGE`；未知类型按 §9.3 R11 处理 |
| `data` | ✅ | 仅该维度字段（映射见 `data-model/data-model.md` §8.4）；**禁止包含敏感明文** |

#### EP-007 / EP-008：定时任务

| 入口 | 任务 | 周期 | 逻辑 | 约束 |
|------|------|------|------|------|
| EP-007 | 缓存对账 | `${device.cache.reconcile.cron}`（建议每 5 分钟，待确认） | 按 `update_time > 上轮水位` 增量查主档 → `MGET` 缓存 → 比对 Value 内 `update_time` → 不一致或缺失则 `DEL` | 单批 ≤ `${device.cache.reconcile.batch-size}`（建议 500）；任务最大时长 < 周期 80%；水位持久化，进程重启不丢；**修复动作只允许 `DEL`，禁止回写新值**（与并发重建竞争会写出更旧的值）；水位丢失时以 `now - 2 × 周期` 冷启动，禁止全表扫 |
| EP-008 | 归档清理 | 每日低峰 | `mq_consume_record` 按 `consume_time` 分批 `DELETE ... LIMIT ${archive.delete.batch-size:1000}`，保留 `${device.consume.record-retention-days:90}`（待确认）；`device_info_change_record` 按月分区，直接 `DROP PARTITION` 保留 `${device.record.retention-months:12}`；**每月 25 日预建次月分区并校验 `pmax` 行数**（为空则告警） | 【强制】分批删除避免大事务；**`mq_consume_record` 不分区**，原因见 `data-model` §8.3；`pmax` 漏建分区会导致数据无法清理，见 `data-model` §6.2 注记；`pmax` 非空时**本轮禁止执行 `DROP PARTITION`**（先补建漏月分区，避免连带删除未知数据）；保留期或批量为 0/负数时任务拒绝执行并 ERROR，禁止提供"清空全部"入口 |

> **G-001（V1.2.0 回写）定时任务跨实例互斥**：§12.1 只声明三进程共用同一 MySQL/Redis，**没有"单实例部署"约束**，而 `device-ingest` 可能多实例水平扩容。Spring `@Scheduled` 是**每实例独立触发**的，两个任务会重复跑：同批 SN 被多实例重复 `MGET`/`DEL`（对账自身成为写放大源，与上表"任务最大时长 < 周期 80%"的意图相反），且 `DROP PARTITION` 重复执行会因元数据锁与"分区不存在"报错。
>
> **处置**：新增组件 `CronLock`，两个任务（EP-007 对账、EP-008 清理与分区预建）进入任务体前必须抢占：
> - Key：`device:lock:cron:{job}`（`job` ∈ `cache-reconcile` / `archive-cleanup` / `partition-prebuild`），`SETNX` + TTL = 周期 × `${device.cron.lock-ttl-ratio:0.8}`；抢不到直接跳过本轮并记跳过计数。
> - 任务结束主动释放；实例崩溃时靠 TTL 自然过期，最长损失一轮。
> - **V1.3.0 补不变式**：锁 TTL = 周期 × 0.8，因此**单次任务时长必须 < TTL**，否则锁先过期、另一实例进场并发跑。本期**不实现 watchdog 续期**（续期需要后台线程与 fencing token，成本高于该场景收益）。落地为两条：① 任务体每批之间检查剩余锁时长，低于 `${device.cron.lock-safety-margin-ratio:0.2}` × TTL 即主动截断本轮、下轮续跑（与 EP-007 已有的"单轮超时截断"同一条路径）；② 记录 `cron.run.duration` 指标并在时长 > TTL × 0.8 时告警。首次清理历史积压（EP-008 可能一次删多年分区）必须在人工监督下执行，见 `data-model` §9.3 回滚预案。
> - 该锁**只保证互斥，不保证 exactly-once**：EP-007/EP-008 的幂等性仍由"只 `DEL` 不写库 + 分批删除可重入"保证。
> - 替代方案（不采纳）：ShedLock / Quartz 集群模式 —— 引入新组件与表，收益不抵本期规模；单实例部署 —— §12.1 未授权该约束且限制扩容。

#### EP-012：维度权威源配置加载

进程启动全量加载 `device_dim_source_auth` 至 `volatile Map`，`@Scheduled` 每 60s 刷新。行数极少（< 100），无缓存一致性负担。**消费与后台写都必须经过该守卫**——它是 TD-004 下唯一确定的覆盖防线。

---

## 6. 整体设计概述

### 6.1 设计目标

1. 支撑 FR-001~FR-006：外部变更准确落地、对外稳定查询、后台可维护可追溯。
2. **数据正确性目标（可验证）**：重复消息零副作用；同 SN 并发写零丢失更新；非授权源改写维度 100% 被拒并留痕。
3. 读侧性能目标（假设值，待压测）：EP-001 缓存命中 P99 ≤ `${device.openapi.p99-ms}`（建议 100ms）；命中率 ≥ 90%（前提：外部查询以热点 SN 为主，待确认）。
4. 可观测（NFR-004）：落地延迟、消费 lag、乐观锁冲突率、重复率、未授权来源计数、缓存命中率 6 项指标可查可告警。
5. **明确不承诺**：业务乱序防护（TD-004）；缓存强一致（口径为最终一致，窗口 ≤ 对账周期）。

### 6.2 技术方案简述

设备主档是**外部系统数据在本服务端的镜像 + 查询加速层**，本系统不定义生命周期流转规则。写侧为 Kafka 单 Topic 按 `msg_type` 分派的异步消费，在**同一本地事务**内完成"幂等记录插入 + 分维度 CAS 更新 + 前后值留痕"，提交后删缓存；读侧三进程分离（ingest / openapi / admin），对外查询走 Redis Cache-Aside 并配增量对账。正确性建立在**幂等唯一索引、维度所有权、乐观锁**三根支柱上，乱序防护因外部契约缺 `event_time` 而显式放弃。

### 6.3 系统架构图

```mermaid
flowchart TD
    subgraph EXT["外部（不受本系统控制）"]
        Producer["🔗 外部生产者<br/>资产系统 / 设备平台 / 回收系统"]
        Caller["👤 外部调用方（持 AppKey）"]
    end

    subgraph ENTRY["接入层"]
        KC["📨 Kafka Consumer（ingest）<br/>EP-006 device.info.change"]
        OA["🌐 OpenAPI Controller（openapi）<br/>EP-001 / EP-002"]
        AC["🌐 Admin Controller（admin）<br/>EP-003~005, 009~011"]
        CRON["⏰ 定时任务（ingest）<br/>EP-007 对账 / EP-008 归档"]
    end

    subgraph BIZ["业务逻辑层（device-core 共享，唯一模型与唯一事务入口）"]
        DISPATCH["🔧 MessageDispatcher<br/>msg_type → 策略分派"]
        GUARD["🛡️ DimAuthGuard<br/>EP-012 维度所有权校验"]
        APPLIER["🔧 DeviceInfoApplier<br/>@Transactional 幂等 + CAS"]
        QRY["📦 DeviceInfoQueryService<br/>Cache-Aside + 击穿互斥"]
        ADMIN["📦 DeviceAdminService<br/>乐观锁 + maintain_*"]
    end

    subgraph REPO["数据访问层"]
        DMapper["🗄️ DeviceMapper（分维度动态列）"]
        RMapper["🗄️ Consume/ChangeRecord/DeadLetter Mapper"]
        CCache["⚡ DeviceInfoCache"]
    end

    subgraph INFRA["基础设施"]
        DB[("💾 MySQL 8.0<br/>device_info / device_info_change_record<br/>mq_consume_record / mq_dead_letter<br/>device_dim_source_auth")]
        RD[("⚡ Redis<br/>device:cache:info:*<br/>mq:dedup:*<br/>device:lock:cache:rebuild:*")]
        KF[("📨 Kafka<br/>device.info.change（+.dlq）")]
    end

    Producer --> KF --> KC --> DISPATCH --> GUARD --> APPLIER
    Caller --> OA --> QRY
    AC --> ADMIN
    CRON --> QRY
    CRON --> DMapper
    APPLIER --> RMapper
    APPLIER --> DMapper
    ADMIN --> DMapper
    APPLIER -.->|"afterCommit DEL"| CCache
    ADMIN -.->|"afterCommit DEL"| CCache
    QRY --> CCache
    DMapper --> DB
    RMapper --> DB
    CCache --> RD
    APPLIER -->|"重试超限"| KF

    style GUARD fill:#ffe0e0
    style APPLIER fill:#d4edda
    style QRY fill:#cce5ff
    style KF fill:#fff3cd
```

> 文字说明：三个进程共用同一份 `device-core` 与同一个 MySQL、Redis 实例，因此写侧删缓存不存在跨进程通知问题。红= `DimAuthGuard`（乱序缺口下的核心防线），绿= `DeviceInfoApplier`（唯一持有事务的组件），蓝= 对外查询（永不写库），黄= Kafka（至少一次语义的来源，也是幂等设计的根因）。

### 6.4 用例图

> mermaid 无原生用例图，以"参与者 + 用例分区"近似，语义由 §6.5 文字用例表兜底。

```mermaid
flowchart TD
    subgraph SYS["系统边界 —— 设备信息管理"]
        UC1["🔲 查询设备信息<br/>(FR-004, FR-005)"]
        UC2["🔲 批量查询设备信息<br/>(FR-004, FR-005)"]
        UC3["🔲 推送变更并落地<br/>(FR-001~FR-003)"]
        UC4["🔲 维护设备档案<br/>(FR-006)"]
        UC5["🔲 排查变更流水 / 重放死信<br/>(FR-001, FR-006)"]
        UC6["🔲 缓存对账与归档<br/>(FR-005, NFR-002)"]
        UC7["🔲 配置维度权威源<br/>(FR-001)"]
    end

    Caller["👤 外部调用方"]
    Producer["🔗 外部系统"]
    Op["👨‍💼 运营人员"]
    Ops["🧑‍🔧 运维人员"]
    Sched["⏰ 调度系统"]

    Caller --> UC1
    Caller --> UC2
    Producer --> UC3
    Op --> UC4
    Op --> UC5
    Ops --> UC5
    Ops --> UC7
    Sched --> UC6
    UC3 -.->|"include 维度所有权校验"| UC7

    style UC3 fill:#d4edda
    style UC1 fill:#cce5ff
    style UC6 fill:#fff3cd
```

### 6.5 文字用例表

| 用例ID | 用例名 | 参与者 | 前置条件 | 主流程 | 异常流 | 关联需求 |
|--------|-------|--------|---------|--------|--------|---------|
| UC-01 | 查询单设备 | 外部调用方 | AppKey 有效、设备存在 | 验签 → 缓存/回源 → 返回白名单字段 | 不存在或已逻辑删 → `404/4040`；限流 → `429/4290`；验签失败 → `401/4010` | FR-004,005 |
| UC-02 | 批量查设备 | 外部调用方 | SN 数量 ≤ 上限 | MGET → miss 回源 IN 查询 → 逐条 `found` | 超上限 → `400/4003`；部分不存在 → `missing` 非空、整体 `200` | FR-004,005 |
| UC-03 | 推送变更落地 | 外部系统 | Topic 可写、`msg_type` 已知、source 已授权 | 校验 → 幂等 → 事务 CAS → 提交后删缓存 → ack | 重复 → 直接 ack；未知类型 → 计数 + ack；异常超限 → 死信 | FR-001~003,005 |
| UC-04 | 维护档案 | 运营人员 | 有 PROFILE 写权限 | 校验 → 乐观锁更新 → 写 `maintain_*` → 删缓存 | 版本冲突 → `409/4091`；未授权维度 → `403/4031` | FR-003,006 |
| UC-05 | 排查与重放 | 运营/运维 | 有后台权限 | 查流水 / 查死信 → 重放 → 复用同一 Applier | 重放仍失败 → 保持待处理 + 告警 | FR-001,006 |
| UC-06 | 对账与归档 | 调度系统 | 任务未被禁用 | 增量比对删缓存 / 分批删历史、DROP 分区 | 单轮超时 → 截断并告警，下轮续跑 | FR-005, NFR-002 |
| UC-07 | 配置维度权威源 | 运维人员 | 有配置权限 | 增删 `(dim_type, source)` 授权 | 漏配 → 新源消息被静默拒（R-005 告警） | FR-001 |

---

## 7. 数据流与信息流 ⭐

### 7.1 数据流图（DFD）

```mermaid
flowchart LR
    ES["🔗 外部系统"]
    CL["👤 外部调用方"]
    OP["👨‍💼 运营人员"]

    subgraph L1["入口层"]
        T1["Kafka device.info.change"]
        A1["GET /devices/{sn}"]
        A2["POST /devices/batch"]
        A3["/admin/devices*"]
    end

    subgraph L2["处理层"]
        P1["反序列化 + 信封校验"]
        P2["维度授权校验"]
        P3["事务：幂等 + 分维度 CAS"]
        P4["缓存读取 / 回源"]
        P5["白名单装配 + 哨兵→null"]
    end

    subgraph L3["存储层"]
        D1[("device_info")]
        D2[("mq_consume_record")]
        D3[("device_info_change_record")]
        D4[("mq_dead_letter")]
        R1[("Redis device:cache:info")]
    end

    ES -->|"① 变更消息 JSON"| T1 --> P1
    P1 -->|"② DimChangeCommand"| P2
    P2 -->|"③ 授权通过"| P3
    P3 -->|"④ INSERT"| D2
    P3 -->|"⑤ UPDATE 本维度列"| D1
    P3 -->|"⑥ INSERT 前后值"| D3
    P3 -->|"⑦ afterCommit DEL"| R1
    P1 -->|"不可重试异常"| D4
    CL -->|"⑧ 查询"| A1 --> P4
    CL --> A2 --> P4
    P4 <-->|"命中 / 回填"| R1
    P4 -->|"miss 回源 SELECT"| D1
    P4 --> P5
    OP --> A3 --> P3
    A3 -->|"流水查询"| D3
```

### 7.2 业务实体映射表（技术 ↔ 需求术语）

| 需求术语 | 定义 | 技术实体 | 数据库表 | 核心字段 | 状态枚举 |
|---------|------|----------|----------|---------|---------|
| 设备 | 一台充电宝设备的资产主档 | `DeviceInfo` | `device_info` | `sn` | — |
| 出厂注册 | 设备在外部完成出厂登记 | `DeviceFactoryDim` | `device_info` | `factory_code, factory_name, produce_time, register_status` | 0 未注册 / 1 已注册 |
| 在线状态 | 设备当前是否在线，由外部判定后推送 | `DeviceOnlineDim` | `device_info` | `online_status` | 0 未知 / 1 在线 / 2 离线 |
| 是否绑定 | 设备是否绑定某目标 | `DeviceBindDim` | `device_info` | `bind_status, bind_target, bind_time` | 0 未绑定 / 1 已绑定 |
| 是否回收/报废 | 外部推送的处置结果状态（**非流程**） | `DeviceRecoverDim` | `device_info` | `recover_status, scrap_time` | 0 正常 / 1 已回收 / 2 已报废 |
| 设备档案 | 型号、备注、图片等可维护信息 | `DeviceProfileDim` | `device_info` | `model_code, model_name, remark, image_url` | — |
| 最新维护人 | 最近一次后台编辑的操作人（拼音账号） | `DeviceMaintainDim` | `device_info` | `maintain_user, maintain_time` | — |
| 变更通知 | 外部一条维度变更消息 | `DeviceChangeMessage` | `mq_consume_record` + `device_info_change_record` | `source, msg_id` | `result`: 1 已更新 / 3 设备不存在 / 4 未授权来源 |

### 7.3 全链路数据变换追踪（EP-006）

| 步骤 | 组件 | 输入数据 | 变换动作 | 输出数据 | 对应需求 |
|------|------|----------|----------|----------|---------|
| 1 | Kafka Listener | 原始 JSON | 反序列化 | `DeviceChangeMessage` | FR-001 |
| 2 | `MessageEnvelopeValidator` | 消息对象 | 必填校验 + `data` 字段按 `msg_type` 白名单校验 | 合法 `DimChangeCommand` / 拒绝 | FR-001 |
| 3 | `DedupGuard`（Redis） | `source + msg_id` | `SETNX mq:dedup:{source}:{msg_id}` | 新消息继续 / 重复返回 | FR-002 |
| 4 | `DimAuthGuard` | Command + 授权 Map | `(msg_type → dim_type)` 是否授权给 `source` | 通过 / `UNAUTHORIZED_SOURCE` | FR-001 |
| 5 | `DeviceInfoApplier`（事务起点） | Command | INSERT `mq_consume_record` | 唯一键：成功 / 冲突 | FR-002 |
| 6 | ↑ 事务内 | `sn` | SELECT 主档取 `version` | `DeviceInfo` / 不存在 | FR-003 |
| 7 | ↑ 事务内 | 本维度列集合 | `UPDATE ... WHERE sn=? AND version=?`（动态列） | 影响行数 1 / 0 | FR-001,003 |
| 8 | ↑ 事务内 | before / after 值 | INSERT `device_info_change_record(result=1/3/4)` | 流水留痕 | FR-006 |
| 9 | ↑ `afterCommit` 钩子 | `sn` | `DEL device:cache:info:{sn}` | 缓存失效 | FR-005 |
| 10 | Listener | `ApplyResult` | 提交 offset / 抛异常触发重试 / 落死信 | ack 或重试 | FR-001 |

### 7.4 状态机图

**（a）消息处理状态机（本项目可控的流转）**

```mermaid
stateDiagram-v2
    [*] --> RECEIVED : Kafka 拉取到消息 (FR-001)
    RECEIVED --> REJECTED : 信封/字段校验失败 (FR-001)
    RECEIVED --> DUPLICATED : Redis 命中或唯一索引冲突 → ack (FR-002)
    RECEIVED --> UNAUTHORIZED : 维度未授权给该 source → 计数 + ack (FR-001)
    RECEIVED --> PROCESSING : 进入事务 (FR-001)
    PROCESSING --> SUCCESS : CAS 影响行数=1 → 提交 → 删缓存 → ack (FR-001,005)
    PROCESSING --> NOT_FOUND : 主档无该 sn → 留痕 result=3 → ack (FR-001)
    PROCESSING --> CONFLICT : version 冲突 → 回滚 → 释放幂等键 (FR-003)
    CONFLICT --> PROCESSING : 未超重试上限
    CONFLICT --> DEAD : 超过上限 → 落 mq_dead_letter → ack + 告警
    REJECTED --> DEAD : 反序列化失败等不可重试
    SUCCESS --> [*]
    DUPLICATED --> [*]
    UNAUTHORIZED --> [*]
    NOT_FOUND --> [*]
    DEAD --> PROCESSING : EP-011 人工重放
```

**（b）设备各维度状态（流转由外部决定，本系统不校验）**

```mermaid
stateDiagram-v2
    state "register_status 出厂注册" as R
    state "online_status 在线" as O
    state "recover_status 回收报废" as C
    R: 0 未注册 → 1 已注册（外部 FACTORY_REGISTER）
    O: 0 未知 ⇄ 1 在线 ⇄ 2 离线（外部 ONLINE_CHANGE，可反复）
    C: 0 正常 → 1 已回收 → 2 已报废（外部 RECOVER_CHANGE，本系统不校验方向）
```

> 本系统**没有设备生命周期状态机**：四个维度彼此正交、由外部独立推送，取值见附录 B。`recover_status=2` 之后是否禁止改写属外部业务规则，本期不校验（§14 R-004）。

---

## 8. 三角追溯矩阵 ⭐

| 入口（接口） | 业务功能 | 操作的数据表 | 涉及字段 | 数据变更类型 |
|------------|---------|-------------|---------|------------|
| EP-006 消费 | FR-001,002,003 | `mq_consume_record` | `source, msg_id, biz_type, biz_id, msg_type, status` | INSERT |
| EP-006 消费 | FR-001,003 | `device_info` | 按 `msg_type` 命中的**单一维度列集合** + `version, update_time` | UPDATE（CAS） |
| EP-006 消费 | FR-006 | `device_info_change_record` | `sn, source, msg_id, dim_type, before_json, after_json, result` | INSERT |
| EP-006 消费 | FR-001 | `mq_dead_letter` | `msg_id, topic, consumer_group, content, fail_reason, retry_count, status` | INSERT |
| EP-006 消费 | FR-005 | Redis | `device:cache:info:{sn}` | DEL（afterCommit） |
| EP-012 | FR-001 | `device_dim_source_auth` | `dim_type, source, enabled` | SELECT |
| EP-001 / EP-002 | FR-004,005 | Redis → `device_info` | §5.2 白名单全字段 | SELECT（回源） |
| EP-003 | FR-006 | `device_info` | 列表列 + 筛选列 | SELECT |
| EP-004 | FR-006 | `device_info`, `device_info_change_record` | 全量建档字段 | INSERT |
| EP-005 | FR-003,006 | `device_info`, `device_info_change_record` | PROFILE 列 + `maintain_user, maintain_time, version` | UPDATE（CAS） |
| EP-009 | FR-006 | `device_info` + Redis | `deleted, version` | UPDATE + DEL |
| EP-010 | FR-006 | `device_info_change_record` | 流水全字段 | SELECT |
| EP-011 | FR-001 | `mq_dead_letter` → 复用 EP-006 链路 | `status` | SELECT + UPDATE |
| EP-007 | FR-005 | `device_info` + Redis | `update_time` | SELECT + DEL |
| EP-008 | NFR-002 | `mq_consume_record` / `device_info_change_record` | `consume_time` / 分区 | DELETE / DROP PARTITION |

> 每条设计内容均可追溯到需求编号，每张被写的表均可追溯到入口——反向核查同表：`device_dim_source_auth` 唯一写入方是运维配置入口 UC-07（EP-012 只读），本期不提供 API 写，需人工 SQL 或后续补接口（记为待办，附录 E #4）。

---

## 9. 详细设计（后端）

### 9.1 核心业务逻辑时序图

#### EP-006：设备变更消息消费（核心链路）

```mermaid
sequenceDiagram
    participant K as 📨 Kafka<br/>device.info.change
    participant L as Listener<br/>DeviceChangeConsumer
    participant V as EnvelopeValidator
    participant RD as ⚡ Redis
    participant G as DimAuthGuard
    participant AP as DeviceInfoApplier<br/>@Transactional
    participant CR as mq_consume_record
    participant DI as device_info
    participant REC as device_info_change_record
    participant DB as 💾 MySQL

    K->>L: ConsumerRecord(key=sn, value=JSON)
    L->>V: 反序列化 + 必填/白名单校验
    alt 校验失败（不可重试）
        V-->>L: Invalid
        L->>DB: INSERT mq_dead_letter
        L->>K: 提交 offset（ack）
    else 校验通过
        L->>RD: SETNX mq:dedup:{source}:{msg_id} EX
        RD-->>L: false → 判重复 → 计数 → ack
        L->>G: (dim_type, source) 是否授权
        G-->>L: DENY → WARN + 计数 → ack（不进事务，不留痕）
        Note over L,AP: 事务外层：重试循环，本身无事务
        loop ≤ max-retry，指数退避
            L->>AP: apply(command) 【事务开始】
            activate AP
            AP->>CR: INSERT(source, msg_id, biz_id...)
            CR->>DB: INSERT
            alt 唯一键冲突
                DB-->>CR: DuplicateKeyException
                AP-->>L: 事务回滚
                L->>K: 提交 offset（重复消息必须 ack，不重投）
            else 插入成功
                AP->>DI: SELECT sn 取 version
                DI->>DB: SELECT
                alt 主档不存在
                    AP->>REC: INSERT result=3
                    AP-->>L: 提交（留痕但不更新）→ ack
                else 存在
                    AP->>DI: UPDATE 本维度列, version=version+1<br/>WHERE sn=? AND version=?
                    DB-->>DI: affected rows
                    alt rows = 0（乐观锁冲突）
                        AP-->>L: 抛 OptimisticConflict → 回滚
                        L->>RD: DEL mq:dedup:{source}:{msg_id} ★ 释放，否则重试被吞
                    else rows = 1
                        AP->>REC: INSERT before_json/after_json, result=1
                        AP->>AP: 注册 afterCommit 钩子
                        AP-->>L: 提交成功
                        deactivate AP
                        L->>RD: afterCommit: DEL device:cache:info:{sn}
                    end
                end
            end
        end
        Note over L,DB: 超过 max-retry → INSERT mq_dead_letter(status=0) + 告警 → ack
        L->>K: 手动提交 offset（仅成功/重复/死信已落库三种终态）
    end
```

#### EP-001：查询单个设备信息（读侧）

```mermaid
sequenceDiagram
    participant C as 👤 外部调用方
    participant I as SignatureAuthInterceptor
    participant QS as DeviceInfoQueryService
    participant RD as ⚡ Redis
    participant MK as DeviceMapper
    participant DB as 💾 MySQL
    participant AS as PublicAssembler

    C->>I: GET /api/v1/devices/{sn} + 签名头
    I->>I: 验签（HMAC + ±5min 时间窗）+ AppKey 配额
    alt 验签失败 / 限流
        I-->>C: 401 {code:4010} / 429 {code:4290, Retry-After}
    end
    I->>QS: 放行
    QS->>RD: GET device:cache:info:{sn}
    alt 命中正常值
        RD-->>QS: JSON
    else 命中空值哨兵 __EMPTY__
        RD-->>QS: "__EMPTY__"
        QS-->>C: 404 {code:4040}（穿透防护，不回源 DB）
    else 未命中
        QS->>RD: SETNX device:lock:cache:rebuild:{sn} EX 3
        alt 抢到重建锁
            RD-->>QS: true
            QS->>MK: selectWhitelistBySn(sn)
            MK->>DB: SELECT 白名单列 WHERE sn=? AND deleted=0
            DB-->>MK: row / empty
            MK-->>QS: DeviceInfo / null
            alt 不存在或已逻辑删
                QS->>RD: SET device:cache:info:{sn} = "__EMPTY__" EX 60
            else 存在
                QS->>RD: SET key=JSON TTL = base + random(jitter)
            end
            QS->>RD: DEL device:lock:cache:rebuild:{sn}
        else 未抢到锁
            RD-->>QS: false
            QS->>RD: 自旋 GET（≤ N 次 × 短睡）
            alt 等待超时仍无值
                QS->>MK: 降级直查 DB（**不回填**，避免并发写脏）
            end
        end
    end
    QS->>AS: 白名单裁剪 + 哨兵时间 → null
    AS-->>C: 200 {code:0, message:"success", data:{...}}
```

#### EP-002：批量查询设备信息

`MGET device:cache:info:{sn1..snN}` → 命中项直接装配；`__EMPTY__` 项归入 `missing`；miss 集合执行一次 `SELECT ... WHERE sn IN (...) AND deleted=0`；存在的用 Pipeline 回填（含 TTL 抖动），不存在的写空值哨兵并归入 `missing`。**条数超上限直接 `400/4003`，服务端不拆分、不分批**。

#### EP-005：后台修改设备档案（写侧）

```mermaid
sequenceDiagram
    participant OP as 👨‍💼 运营
    participant AC as AdminController
    participant AS as DeviceAdminService
    participant G as DimAuthGuard
    participant MK as DeviceMapper
    participant DB as 💾 MySQL
    participant REC as ChangeRecordMapper
    participant RD as ⚡ Redis

    OP->>AC: PUT /api/v1/admin/devices/{sn} {profile..., version}
    AC->>AS: AdminUpdateCommand + 操作人拼音账号
    AS->>G: (PROFILE / MAINTAIN, ADMIN) 是否授权
    alt 未授权
        G-->>AS: DENY
        AS-->>OP: 403 {code:4031 该维度仅允许外部源写入}
    end
    Note over AS,DB: @Transactional(rollbackFor = Exception.class, timeout = 3)
    AS->>MK: updateCas(sn, PROFILE 列 + maintain_*, version)
    MK->>DB: UPDATE ... WHERE sn=? AND version=?
    DB-->>MK: affected rows
    alt rows = 0
        MK-->>AS: 冲突
        AS-->>OP: 409 {code:4091 数据已被外部更新，请刷新后重试}（**不静默重试**）
    else rows = 1
        AS->>REC: INSERT dim_type=PROFILE, before/after, result=1, op_user
        AS-->>AC: 提交成功
        AC->>RD: afterCommit DEL device:cache:info:{sn}
        AC-->>OP: 200 {code:0, data:{version: 新版本}}
    end
```

#### EP-003 / EP-004 / EP-009 / EP-010：标准 CRUD 时序要点

| 入口 | 时序要点 | 与上面的差异 |
|------|---------|------------|
| EP-003 列表 | 参数校验（强制筛选条件 + 分页 `page/page_size`）→ 分页查询 → 装配返回 | 只读、无事务；**不查也不写缓存**（缓存只服务对外点查） |
| EP-004 新增 | 授权检查（`ADMIN` 是否有 PROFILE 写权限）→ 校验 + `Idempotency-Key` 去重 → INSERT（`uk_sn` 冲突 → `409/4090`）→ 流水 INSERT(`dim_type=PROFILE, result=1`) → afterCommit `DEL device:cache:info:{sn}` | **必须删缓存**：该 SN 可能已被查询过并留下 `__EMPTY__` 哨兵（最长 60s），不删则新设备建好后仍对外 404（§11.2 缺陷 9） |
| EP-009 逻辑删 | 取 `version` → CAS 置 `deleted=1` → 流水 → afterCommit `DEL` 缓存 | 与 EP-005 同构；删后下次查询会重新写 `__EMPTY__` 哨兵 |
| EP-010 流水查询 | `WHERE sn=? ORDER BY create_time DESC` 走各分区的**局部** `idx_sn_create_time`；**V1.3.0 更正**：该语句不含分区键条件，因此**没有分区裁剪**（旧表述"按时间倒序天然裁剪到最近分区"不成立）。代价是同一 `sn` 要在保留期内每个月份分区各做一次索引查找再归并排序，保留 12 个月即 ≤ 12 次等值探查；因单 SN 行数有界（假设日均 43 条 / 10 万设备 ≈ 每设备每日 < 1 条），成本可接受 | 只读；若后续要求"只查最近 N 天"，必须显式带 `create_time` 范围才能触发裁剪（附录 E #14） |

### 9.2 交互图（跨模块调用）

```mermaid
sequenceDiagram
    participant M1 as device-ingest<br/>DeviceChangeConsumer
    participant Core as device-core<br/>DimAuthGuard / DeviceInfoApplier
    participant M2 as device-admin<br/>DeviceAdminService
    participant M3 as device-openapi<br/>DeviceInfoQueryService
    participant DB as MySQL
    participant RD as Redis

    M1->>Core: apply(DimChangeCommand)
    Core->>DB: 幂等 + 分维度 CAS + 流水（同事务）
    Core-->>M1: ApplyResult
    M1->>RD: afterCommit DEL cache
    M2->>Core: apply(AdminUpdateCommand)（复用同一守卫与 CAS）
    M2->>RD: afterCommit DEL cache
    M3->>RD: GET cache
    M3->>DB: miss 回源 SELECT（只读，永不写）
    Note over Core,M3: 唯一的模型、唯一的事务入口都在 device-core；<br/>三个进程不得各自实现更新逻辑，防止写路径分叉
```

### 9.3 核心业务逻辑规则（写侧，逐条可验证）

| # | 规则 | 实现要点 | 违反后果 |
|---|------|---------|---------|
| R1 | 幂等记录与业务更新**必须同一事务** | `@Transactional` 内先 INSERT `mq_consume_record`，再 UPDATE 主档 | 先提交幂等记录 → 业务失败后重投被判重复 → **数据永久丢失** |
| R2 | 重复消息必须 **ack**，不抛异常 | 在事务**外层**捕获 `DuplicateKeyException` | 事务内 catch 会把事务标记 rollback-only；向外抛则无限重投 + 积压 |
| R3 | Redis 幂等键在回滚路径**必须释放** | 冲突回滚后 `DEL mq:dedup:*`（外层或 `afterCompletion` 判 `STATUS_ROLLED_BACK`） | 重试消息被 Redis 挡成"重复"直接 ack → **消息被吞**（`mq_specification.md` §3.1 正例同款处理） |
| R4 | 乐观锁重试在事务**外层** | 重试循环无事务，跨 Bean 调用独立事务方法 | 事务内重读拿到同快照 `version`，重试必然再失败；自调用还会导致事务完全失效 |
| R5 | 消费者重试 / 后台提示，**策略不同** | 消费者退避重试 ≤ `max-retry`；后台直接 `409/4091` | 后台静默重试会用过期编辑内容覆盖外部最新值 |
| R6 | 逻辑删设备**仍接受外部维度更新**，但不改 `deleted`（查询侧继续不可见） | 消费按 `sn` 命中即更新维度列，不修改 `deleted`；复活只由后台显式操作 | 消费端过滤 `deleted` → 外部重推被静默丢弃，数据缺口无人知晓 |
| R7 | 一条消息一个事务（小事务） | 逐条 apply，禁止整批一个事务 | 一条冲突连坐整批回滚；长事务占用连接、放大主从延迟 |
| R8 | 事务内禁止 Redis / HTTP / 发消息 | 缓存删除走 `TransactionSynchronization.afterCommit` | 事务未提交即对外可见 → 脏数据扩散；回滚后消息已发（MQ 规范 §2.2【强制】） |
| R9 | `rollbackFor = Exception.class` 显式声明 | 注解上声明 | 默认仅回滚 `RuntimeException`，受检异常导致"流水成功、主档未改"的脏留痕 |
| R10 | **分维度动态列更新** | MyBatis `<set>` 只拼本维度列，禁止全字段 update | 抹掉其他源刚写入的维度值（本项目最危险的数据正确性陷阱） |
| R11 | 未知 `msg_type` 不抛异常 | WARN 日志 + `unauthorized_type` 计数 + ack | 一个未知类型卡住分区、Topic 全线积压 |
| R12 | 仅不可重试异常进死信 | 反序列化失败无可信 `sn`，无法写业务流水，只能落死信表 | 死信必须可查询可重放（MQ 规范 §3.3【强制】） |
| R13 | offset 只在三种终态提交 | 成功 / 判定重复 / 死信已落库 | 事务未提交先提交位点 → 崩溃即丢消息 |
| R14 | **动态 SQL 标识符只能来自代码常量**（V1.3.0 把范围从"列名"扩到"列名 + 分区名"）：列名取自 `DimSpec`，分区名取自 `CronSql` 的 `YYYYMM` 生成规则；禁止用消息 `data` 的 key、任务参数或任何外部输入当标识符 | `<set>` 拼接的列名取自代码枚举白名单；`data` 的每个 key 先与白名单求交集，**不在白名单即整体拒绝**（§7.3 步骤 2）。列名不能参数化，只能用 `#{}` 绑值（`java_middleware_specification.md` §MyBatis【强制】禁 `${}` 拼值）。DDL 侧同理：`ADD/DROP PARTITION` 的分区名必须能被 `^p\d{6}$` 匹配，且**任务不接受任何外部入参**（EP-008 只由 `YearMonth.now()` 推算） | 把外部可控的字符串当 SQL 标识符 = **SQL 注入面**；一条恶意 `data` 即可改写任意列，一个外部传入的分区名即可 `DROP` 掉整个月分区（数据不可恢复） |
| R15 | 停机顺序：先停止拉取 → 等在途消息到终态 → 提交位点 → 关容器 | `server.shutdown=graceful` + `phase` 排序，Kafka 容器 `stop()` 早于 DataSource/Redis 关闭（`java_springboot_specification.md` §graceful【强制】） | 容器先关、消费者还在拉 → 事务被中断后位点未提交 → 重启重投（靠幂等兜住）；更坏是位点已提交而事务未提交 → **消息永久丢失** |

**处理逻辑分支图**

```mermaid
flowchart TD
    A["收到消息"] --> B{"信封校验通过?"}
    B -- 否 --> B1["落死信 + ack"]
    B -- 是 --> C{"Redis SETNX 成功?"}
    C -- 否 --> C1["判重复 → ack（保留键，不释放）"]
    C -- 是 --> D{"source 被授权写该维度?"}
    D -- 否 --> D1["计数 + WARN → ack（事务外，不留痕）"]
    D -- 是 --> E["【事务】INSERT 幂等记录"]
    E --> F{"唯一键冲突?"}
    F -- 是 --> F1["回滚 → 外层判重复 → ack（重复无需释放键）"]
    F -- 否 --> G{"主档存在?"}
    G -- 否 --> G1["留痕 result=3 → 提交 → ack"]
    G -- 是 --> H["CAS UPDATE 本维度列"]
    H --> I{"影响行数 = 1?"}
    I -- 否 --> I1["回滚 → DEL mq:dedup → 退避重试"]
    I1 --> J{"超过 max-retry?"}
    J -- 否 --> E
    J -- 是 --> J1["落死信 + 告警 + ack"]
    I -- 是 --> K["流水留痕 result=1 → 提交"]
    K --> L["afterCommit DEL 缓存"]
    L --> M["提交 offset"]
```

### 9.4 缓存设计（FR-005）

| 项 | 取值 | 依据 |
|----|------|------|
| Key | `device:cache:info:{sn}` | Redis 规范 §1.2【强制】`业务域:模块:实体:ID` |
| 结构 | String（整条 JSON） | 不用 Hash 分维度：局部 field 失效会引入"部分已删"中间态，收益不抵复杂度 |
| Value | = 对外白名单字段 **+ 一个 `update_time`** | `update_time` 仅供 EP-007 对账，**不出现在响应** |
| TTL | `${device.cache.ttl-base}` + `${device.cache.ttl-jitter}`（建议 1800s + 0~300s，**待确认**） | §2.3【强制】必须 TTL + 随机抖动防雪崩 |
| 空值 | 同 Key 写哨兵 `__EMPTY__`，TTL 建议 60s | §2.4【强制】穿透防护 |
| 写模式 | Cache-Aside：**先更新 DB，后删缓存**；删除而非更新 | §2.2【强制】禁止先删缓存后写库 |
| 失效时机 | 消费成功 / 后台修改 / 逻辑删 —— 三处统一 `afterCommit` 删 | 与 R8 同源 |
| 击穿 | `device:lock:cache:rebuild:{sn}`（`SETNX + EX 3`）；未抢到自旋后降级直查且**不回填** | §2.4【强制】 |
| 雪崩 | TTL 抖动；批量清理用 `SCAN` 逐批，禁 `KEYS`/`FLUSHDB` | §1.4 / §2.4【强制】 |
| 大 Key | 单 Value 估算 < 2KB（远低于 10KB 上限） | §1.3【强制】 |
| 幂等键 | `mq:dedup:{source}:{msg_id}`，TTL 建议 24h，**容量必须按 `峰值msg/s × TTL × 单键字节` 重算** | MQ 规范 §3.1；若内存不达标则缩短 TTL，由 DB 唯一索引兜底（TD-002） |
| 任务互斥锁（V1.2.0 补） | `device:lock:cron:{job}`，`SETNX` + TTL = 周期 × `${device.cron.lock-ttl-ratio:0.8}`；`job` ∈ `cache-reconcile` / `archive-cleanup` / `partition-prebuild` | G-001：EP-007/EP-008 在多实例下会重复跑；Redis 规范 §1.2 四段命名 + §2.3【强制】必设 TTL |
| 对账水位（V1.2.0 补） | `device:cron:watermark:cache-reconcile`，值 = 上轮已处理的 `update_time`，TTL `${device.cron.watermark-ttl-hours:168}` 每轮续期；键丢失则按 `now - 2 × 周期` 冷启动 | G-001；EP-007 要求"水位持久化、进程重启不丢"，而本期不授权新增状态表 |
| 后台建档幂等键（V1.2.0 补） | `device:admin:idem:{Idempotency-Key}`，`SETNX` + TTL `${device.admin.idem-ttl-hours:24}`，存首次结果快照 | HTTP 规范 §9.1【强制】；`uk_sn` 只能挡"不同请求撞同一 SN"，挡不住"同一请求被重发"（后者应返回同一结果而非 `409/4090`） |
| **不做** | 不缓存列表/条件查询结果；`ext_json`、`maintain_*`、控制列不进 Value | 组合筛选命中率低，缓存只制造不一致 |

**一致性口径（对外文档必须照此表述）**：**主动删除负责快，TTL 兜底负责错，定时对账负责补。** 最长脏读窗口 = `min(TTL, 对账周期)`，按建议值约 5 分钟量级；**不承诺强一致**。

### 9.5 数据可见性与字段裁剪

| 字段组 | 进 Redis Value | 对外响应 | 后台可见 | 理由 |
|-------|---------------|---------|---------|------|
| §5.2 EP-001 白名单（15 个业务字段，逐个清单见 `data-model` §7.1） | ✅ | ✅ | ✅ | 需求方裁决：除 `ext_json` 等综合字段外全部放入缓存与对外 |
| `update_time` | ✅ | ❌ | ✅ | 对账所需，非业务信息 |
| `maintain_user` / `maintain_time` | ❌ | ❌ | ✅ | 需求方裁决：维护人存拼音、不进缓存不对外 |
| `ext_json` | ❌ | ❌ | ✅ | 外部透传综合信息；如需对外另设开关（本期不做） |
| `id` / `version` / `deleted` / `create_time` | ❌ | ❌ | ✅ | 内部实现细节，不暴露 |

> 由此得到一个可维护的不变式：**缓存 Value = 对外 DTO 字段集 ∪ {`update_time`}**。任何人想给对外接口加字段，只需同时改这一处 Value 装配，不存在两份字段清单漂移。

### 9.6 统一响应外壳与错误码

```json
{ "code": 0, "message": "success", "data": {}, "trace_id": "01J8Z3X4Y5Z6A7B8C9D0E1F2G3" }
```

**`trace_id` 规则（接口文档依赖，必须定义）**

| 项 | 口径 |
|----|------|
| 生成 | 入站带 `X-Trace-Id` 则沿用（便于调用方串联自己的链路）；否则服务端生成（与响应 `trace_id` 同值） |
| 回写 | **同时写入响应头 `X-Trace-Id` 与响应体 `trace_id`**，两者恒等 |
| 必填性 | 成功与错误响应**都返回**（错误为 HTTP 规范 §5.1【强制】；成功返回是为了"200 但数据不对"的排障场景） |
| 落日志 | 进 MDC，使 `trace_id` 能把一次请求的所有日志行串起来（日志规范【强制】要求可独立定位上下文） |

| HTTP status | `code` | 含义 | 出现入口 |
|-------------|--------|------|---------|
| 200 | 0 | 成功 | EP-001~003, EP-005, EP-007(不适用), EP-010, EP-011 |
| 201 | 0 | 创建成功（HTTP 规范 §3.2 精确状态码） | EP-004 |
| 204 | —（**无响应体**，统一外壳的唯一例外） | 删除成功 | EP-009 |
| 400 | 4001 / 4002 | 参数缺失 / 格式错误 | EP-001~005, EP-011 |
| 400 | 4003 | 批量条数超上限 | EP-002 |
| 400 | 4004 | 缺少必要筛选条件 | EP-003 |
| 401 | 4010 / 4011 | 验签失败 / 时间戳超窗（后台场景 = 未登录或 JWT 失效） | EP-001~011 |
| 403 | 4030 / 4031 | 无权限 / 该维度未授权本来源写入 | EP-004,005,009,011 |
| 404 | 4040 | 资源不存在（设备不存在或已逻辑删除；死信记录不存在） | EP-001, EP-005, EP-009, EP-010, EP-011 |
| 405 | 4050 | 方法不允许 | 全部（HTTP 规范 §3.2） |
| 409 | 4090 / 4091 / 4092 / 4093 | SN 已存在 / 版本冲突需刷新 / 死信重放遇幂等冲突（该 `msg_id` 已消费过）/ 死信已处理不可重放 | EP-004, EP-005, EP-009, EP-011 |
| 429 | 4290 | 超配额限流 | EP-001,002 |
| 500 | 5000 | 内部错误 | 全部 |
| 503 | 5030 | 依赖（Redis/DB）调用超时或不可用，且降级路径仍失败。V1.3.0 更正旧表述"已熔断"：本期**不引入熔断框架**，该码由"超时 + fail-fast"产生（口径见 §11.1 弹性参数与 §12.4 不引入清单） | EP-001,002 |

> **429 与限流头**：`429` 必带 `Retry-After`，同时所有对外响应恒带 `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset`（HTTP 规范 §10.1）。
> **缓存头**：对外查询恒返回 `Cache-Control: no-store`。理由见附录 C —— 本系统已是"最终一致 + 对账窗口"，禁止再叠 HTTP/CDN 缓存使窗口不可观测。
> **204 例外说明**：HTTP 规范 §4.1【强制】要求 JSON 响应统一包装，而 §3.2 规定 DELETE 成功返回 204 无内容；204 无响应体因此不违反 §4.1（不是"JSON 响应未包装"）。这是规范内两处条款的交汇点，明确记录以免实现时被改成 `200 + {code,...}`。
> **EP-010 的 404 语义**：仅当 `sn` 不存在或已逻辑删除时返回 `404/4040`；设备存在但无流水返回 `200` + 空 `items`（避免"查不到"与"没有记录"混淆）。

错误响应必须含 `trace_id`（HTTP 规范 §5.1【强制】）。本仓库口径为"HTTP status 表传输层 + `code` 表业务层"，与其他项目"恒 HTTP 200 + 业务 code"的旧口径不同，**以本仓库规范为准**（附录 C）。

### 9.7 日志要求（与流水表分工，不可互相替代）

| 场景 | 必须打印 | 规范依据 |
|------|---------|---------|
| EP-006 单条消息 | 入口（`source/msg_id/msg_type/sn`）→ 判定结果（成功/重复/未授权/不存在/冲突）→ 终态与耗时 | 日志 §核心流程【强制】关键节点 |
| 消费失败与死信 | WARN/ERROR + **完整堆栈**（异常对象作最后一个参数），禁止只打 `e.getMessage()` | 日志 §异常【强制】 |
| 后台增/改/删 | 操作人（拼音账号）、对象 SN、**变更前后值**、结果与 `version` | 日志 §敏感操作【强制】；业务留痕另由流水表承载 |
| EP-002 批量查询 / EP-008 批量清理 / EP-007 对账 | 请求或处理**总数 + 成功/失败计数**，禁止逐条刷屏 | 日志 §批量操作【强制】 |
| 敏感字段 | `maintain_user`、`remark`、`ext_json` 内容不得整段进日志（只打长度或掩码）；`X-Signature`、AppKey Secret 禁止打印 | 日志 §敏感信息【强制】 |
| 级别 | 生产 INFO 起；重复消息计数属 INFO/DEBUG，未授权来源与死信属 ERROR | 日志 §级别【强制】 |
| 同一异常 | 只在最终处理层打印一次，禁止 Controller/Service/DAO 三层重复打 | 日志 §重复打印【强制】 |

> 分工口径：**日志 = 排障链路（trace_id 关联，可丢可过期）；流水 = 业务事实（不可丢，供对账与人工纠正）**。两者内容重叠是设计使然，不是冗余实现；任何一处缺失都按缺陷处理。

---

## 10. 详细设计（前端）

本期**不含前端实现**。需求方给定的范围为"数据库设计 + 后端 API + Redis 缓存"，运营后台以接口形式交付（EP-003~EP-011），页面由前端后续按 [contracts/api-contract.yaml](contracts/api-contract.yaml)（机器可读契约）与 §5.2、§9.6（设计口径与错误码）对接；本次无 UI 设计文档输入，故不产出组件、视觉 Token 与状态映射。

---

## 11. 技术特性评估 ⭐

> ⚠️ **本节数字全部为假设值或公式**：需求方明确"不预设量级、按可扩展设计"，且无历史流量数据、无调研报告支撑（§1.1）。上线前必须以压测与真实流量重算并升次版本号。**不得把这些数字当作承诺指标。**

### 11.1 并发与性能参数估算

| 参数 | 数值 | 依据/说明 |
|------|------|----------|
| 预估峰值消费 TPS | 假设 500 msg/s | 假设场景：10 万设备 × 日均 43 条变更 ≈ 43 万/日，按峰谷比 10 折算 |
| 单消息事务体积 | 1 SELECT + 2 INSERT + 1 UPDATE | 幂等记录 + 主档 + 流水（含冲突后流水更新） |
| 事务超时 | `${device.ingest.tx-timeout-sec}` 建议 3s | 短事务；事务内禁远程调用（R8） |
| 消费并发数 | `${device.ingest.concurrency}` 建议 3 | **必须 ≤ Topic 分区数**；提高并发前先扩分区 |
| Topic 分区数 | `${device.ingest.partition}` 建议 6 | 分区数即消费并行上限；事后扩分区会短暂打破同 SN 有序（§11.2 风险 5） |
| DB 连接池 | min=10 / max=50（建议，三进程各自配额） | ingest 按 `并发数 × 1 连接` 并留 3 倍余量 |
| Redis 连接池 | min=5 / max=20（建议） | 读侧以 GET/MGET 为主 |
| EP-001 目标 P99 | ≤ 100ms（假设值） | 命中路径 = 1 次 GET + 内存装配 |
| EP-002 单次上限 | `${device.query.batch-max:100}` | 由 MGET 往返与响应包体积共同约束 |
| 乐观锁退避 | `backoff = ${device.ingest.backoff-ms:50} × 2^n`，**± ${device.ingest.backoff-jitter:0.2} 抖动** | Spring Boot 规范【强制】：重试必须有界（次数 + 超时预算 + 带 jitter 的指数退避）。缺 jitter 时并发同 SN 的重试会同步共振、反复撞同一版本 |
| 重试总预算 | `${device.ingest.retry-budget-ms:1500}`，必须 **<** 消费者 `max.poll.interval.ms` 与 rebalance 阈值 | 超预算直接进死信，绝不允许重试把消费线程拖到被踢出消费组（`java_middleware` §Consumer【强制】禁长耗时） |
| 停机 | `server.shutdown=graceful`，且 Kafka 容器停止早于 DB/Redis 资源关闭（R15） | 否则在途消息被中断，行为退化为不可预期的重投/丢位点 |
| 对账单批 / 周期 | ≤ 500 条 / 每 5 分钟（建议） | 防对账自身成为写放大源 |
| 乐观锁冲突率告警 | > 1%（建议） | 突增说明外部并发刷数或维度列分组有误 |
| 消费 lag 告警 | > `${device.ingest.lag-alarm}` 条（建议 10000） | 唯一能提前发现"对外数据开始滞后"的指标 |
| **读侧依赖超时（V1.3.0 补）** | Redis：`spring.data.redis.timeout` 建议 200ms、`connect-timeout` 建议 200ms；MySQL：`mybatis.configuration.default-statement-timeout` 建议 2s、Hikari `connection-timeout` 建议 3s | `java_springboot_specification.md` §九【强制】"每一个出站调用必须设置连接 + 读/响应超时，禁止用无超时默认值"——该条原文针对 HTTP 客户端，本项目把同一约束套到 Redis/MySQL 客户端上。**这是【强制】项，与是否引入熔断器无关** |
| 超时后的行为（V1.3.0 补） | 读侧：Redis 超时/连接失败 → 直接回源 DB 且**不回填**；回源也超时或连接不上 → `503/5030`（fail-fast，不排队等待）。写侧：不做超时降级，事务按 `${device.ingest.tx-timeout-sec}` 回滚 → 退避重试 → 超限进死信 | §11.2 风险 3 的有效防线是"超时 + 有界并发 + 对外限流"三者，熔断器只是【推荐】的锦上添花；V1.2.0 把 503 写成"已熔断"却没有任何组件与配置承接，属把目标态写成现状，本轮更正 |

**V1.2.0 补：实现映射暴露的必需配置键（G-002）**

`implementation-map.md` 逐个 `IM-xxx` 落代码落点时发现，下列参数是代码必须读取的，但 V1.1.0 的参数表没有登记。默认值沿用文档已给出的建议值；**未登记不等于不需要**，缺键会让实现自行硬编码阈值。

| 键 | 默认值 | 用途 | 不变式（启动断言，见 `IM-OPS-001`） |
|----|--------|------|----------------------------------|
| `device.cron.lock-ttl-ratio` | `0.8` | EP-007/EP-008 互斥锁 TTL 占周期比例 | `0 < ratio < 1` |
| `device.cron.lock-safety-margin-ratio` | `0.2` | 任务体剩余锁时长低于 `TTL × 0.2` 时主动截断本轮（V1.3.0 补，见 §5.2 G-001 不变式；本期无 watchdog 续期） | `0 < ratio < 1`；与 `device.cron.lock-ttl-ratio` 属同一启动断言组，两者都合法才允许定时任务注册 |
| `device.cron.watermark-ttl-hours` | `168` | 对账水位 key 的 TTL（每轮续期） | > 对账周期 |
| `device.cache.rebuild-spin-times` | `3` | 未抢到重建锁时的自旋次数上限 | 有界、> 0（禁止无界自旋） |
| `device.ingest.max-payload-bytes` | `262144` | 消息体上限，超限直接落死信 | 与 `data-model` §6.4 `content` 的 256KB 口径同值 |
| `device.openapi.sign-window-seconds` | `300` | HMAC 验签时间窗（±） | 与契约 `securitySchemes.AppKeyHmac` 的 ±5min 一致 |
| `device.openapi.app-secret-path` | `${挂载路径，未定}` | AppKey→Secret 映射文件来源（V1.3.0 补，§12.4 密钥表）。文件内为 `appKey → [当前, 上一版]` 双值，用于轮换窗口内双活校验 | 路径存在且可读；**缺键即拒绝启动**，禁止回落 `application.yml` 明文 |
| `device.openapi.qps-per-app` | `${按调用方配额，未定}` | 按 AppKey 的限流上限 | > 0；未配置时拒绝启动而非默认放行 |
| `device.admin.jwt.issuer` | `${外部 IdP，未定}` | JWT `iss` 校验 | 非空 |
| `device.admin.jwt.audience` | `${外部 IdP，未定}` | JWT `aud` 校验 | 非空 |
| `device.admin.jwt.public-key-path` | `${挂载路径，未定}` | 验签公钥来源 | 路径存在；禁止把密钥写进 yaml |
| `device.admin.idem-ttl-hours` | `24` | 后台建档 `Idempotency-Key` 结果快照 TTL | > 0 |
| `device.archive.cleanup-cron` | `0 30 3 * * ?`（建议低峰） | EP-008 幂等记录清理与分区删除周期 | cron 合法 |
| `device.archive.prebuild-cron` | `0 0 4 25 * ?`（每月 25 日） | EP-008 次月分区预建周期 | 必须早于月末 |

> 表中所有标 `${...未定}` 的键与 §11.1 上表的假设值同性质：**是外部输入，不是本文档可以自定的数字**，上线前由运维/业务填入；代码只保证"缺键即启动失败"，不填默认业务值。

### 11.2 技术缺陷与已知风险

| # | 缺陷/风险点 | 严重程度 | 根因 | 缓解措施 | 是否可接受 |
|---|------------|---------|------|---------|----------|
| 1 | **不防业务乱序**：旧事件后到覆盖新值 | 🔴 高 | 外部消息体缺 `event_time`（违反 MQ 规范 §1.3） | 维度单一权威源 + 流水人工发现 + 后台纠正；TC-006-13 固化行为；提契约诉求 | **是（本期明示接受）** |
| 2 | 缓存与 DB 最长不一致窗口 = 对账周期 | 🟡 低 | Cache-Aside 固有 | 主动删 + TTL + 对账三层；对外声明最终一致 | 是 |
| 3 | Redis 不可用 → 读侧全量回源打穿 DB | 🟠 中 | 单缓存层 | 互斥重建 + 自旋降级 + 对外限流 + **依赖超时后 fail-fast 返回 `503/5030`**（V1.3.0 把"熔断预案"改为可落地的超时口径：本期不引入熔断框架，防线由 §11.1 的超时键 + 有界连接池 + 限流三者承担） | 是（需演练） |
| 4 | 幂等表膨胀拖慢唯一索引 | 🟠 中 | 消息量大 | EP-008 分批删 + 保留期 | 是 |
| 5 | 扩分区导致同 SN 短暂乱序 | 🟠 中 | Kafka 分区映射变更 | 低峰扩 + 幂等表兜底；变更前列入 #1 影响评估 | 是（运维须知写明） |
| 6 | 消费积压 → 对外数据滞后 | 🟠 中 | 单分区吞吐上限 | lag 告警 + 扩分区 + 并发数；必要时响应带新鲜度头 | 是 |
| 7 | 后台编辑被同期外部推送覆盖（同维度） | 🟡 低 | 两者争同一行 | R5 后台不重试直接提示 + 授权表限制后台可写列 | 是 |
| 8 | 无生命周期校验（报废后仍可被改写） | 🟡 低 | 需求方裁决不做流程 | 校验责任归外部；如需则扩展 `DimAuthGuard` 一处 | 是 |
| 9 | `__EMPTY__` 哨兵使"设备随后被新建"存在最长 60s 不可见 | 🟡 低 | 空值缓存 | EP-004 新增设备时同步 `DEL` 该 key | 是 |

### 11.3 技术方案特点与适用说明

**核心技术特点**

1. **镜像型数据模型（本系统不做业务裁决）**
   - 描述：四个维度全部由外部推送，本系统只保证"落得准、读得快、追得到"。
   - 影响：无状态机校验代码、并发面窄；但**上游错则下游错**。
   - 运维关注点：`result=3（设备不存在）` 与 `UNAUTHORIZED_SOURCE` 计数是上游数据质量的第一信号。

2. **维度所有权取代时间仲裁**
   - 描述：无业务事件时间时，用"谁能写哪个维度"这一确定约束换取正确性。
   - 影响：新增外部源或新维度**必须同步配置 `device_dim_source_auth`**，否则消息被静默拒。
   - 运维关注点：配置 60s 生效，变更需留操作人。

3. **三进程共库 + 单一事务入口**
   - 描述：`device-core` 持有唯一更新方法，任何进程不得绕过。
   - 影响：缓存失效无需跨进程通知；代价是三进程共享 DB 连接资源，需分别配额。

4. **分维度动态列 + CAS**
   - 描述：一条消息只 `UPDATE` 它负责的列。
   - 影响：这是防跨维度覆盖的**必要条件**，乐观锁只是兜底，两者不可互相替代。

**与同类方案的差异**

| 维度 | 本方案 | 常见替代 | 说明 |
|------|--------|---------|------|
| 消息可靠性 | At-Least-Once + 幂等 | At-Most-Once | 必须配幂等，代价是重复处理开销 |
| 状态一致性 | 最终一致（≤ 对账周期） | 读 DB 强一致 | 用一致性换读吞吐 |
| 顺序保证 | 分区内有序（同 SN） | 全局有序 | 全局有序吞吐极低且跨源仍无序 |
| 乱序处理 | **不处理**（明示缺口） | 业务时间水位 | 缺口源于外部契约 |
| 幂等依据 | DB 唯一索引 | Redis | Redis 不承担正确性 |

**使用本方案的前提条件（逐条必须满足，否则防线失效）**

- [ ] 外部生产者以 `sn` 作为消息 key（否则分区有序性失效）
- [ ] 外部生产者 `enable.idempotence=true` 或单实例串行发送（否则同源同维度可自相乱序）
- [ ] `msg_id` 在**同一 source 内**唯一（跨源不要求，故唯一键为 `(source, msg_id)`）
- [ ] 每个维度有且仅有一个权威 `source`
- [ ] 需求方接受"不防业务乱序"（§14 R-001）

### 11.4 容量规划估算

> 计算依据统一为占位假设：**10 万设备 / 日均 43 万条变更**，待业务给数后重算。

| 资源 | 当前估算 | 1 年后估算 | 计算依据 |
|------|---------|-----------|---------|
| `device_info` | ~40 MB | ~40 MB | 10 万行 × 平均行长 ~400B；主档不随消息量增长 |
| `device_info_change_record` | ~0.9 GB/月 | ~11 GB/年 | 单行 ~700B（含 before/after JSON）× 43 万/日 × 30 |
| `mq_consume_record` | ~0.3 GB/月（90 天滚动） | 不随时间累积 | 单行 ~200B × 43 万/日 × 90 天保留 |
| `mq_dead_letter` | 正常 < 1000 行 | 同 | 持续增长即异常信号，应告警而非扩容 |
| Redis 内存 | ~40 MB（缓存热点 20%）+ 幂等键 | 同 × 增长 | 10 万 key × 2KB × 20% ≈ 40MB；幂等键 = 峰值 msg/s × TTL × 字节 |
| Kafka 存储 | ~30 GB/月 | 同 | 消息 ~300B × 43 万/日 × 留存期 |
| 对外出口带宽 | 依调用方 QPS | 同 | 响应包 ~600B × QPS |

> 结论：**增长最快的是 `device_info_change_record`**，因此该表按月 RANGE 分区 + `DROP PARTITION` 清理；`mq_consume_record` 因唯一索引不能分区（`data-model` §8.3）。

---

## 12. 影响范围与依赖

### 12.1 直接影响的现有模块

| 模块 / 服务 | 影响类型 | 变更内容说明 |
|-----------|---------|------------|
| 无 | — | 仓库当前为空项目（仅 `.aisdd` 规范与 `.qoder` skills），**全新建** |
| 新增 `device-core` | 新建 | 实体、Mapper、`DimAuthGuard`、`DeviceInfoApplier`、`DeviceInfoCache`、DTO 装配；工具链为 **Java 21 + Maven 3.9.16 + Spring Boot 3.5.16**（需求方 2026-10-29 指定；规范基线"Java 17 / Boot 3.2+"，属高于基线）；**表结构本期不启用版本化迁移**：需求方裁决"DDL 不需要迁移"，故不引 `flyway-core`、不落 `db/migration/*.sql`，生产 DDL 由运维按 `data-model` §6 人工执行（Spring Boot §迁移【强制】按"有意偏离 + 豁免留痕"记，见附录 C 同一行；附录 E #12 的两种读法对代码处置一致，编码启动时不再等该裁决）。集成测试的建表夹具为 `device-core/src/test/resources/db/test-schema.sql`，逐字取自 `data-model` §6，仅测试用。另外 core 需以 `provided` 作用域引入 `spring-boot-starter-web`，否则 `IM-CORE-007` 的 Web 类无法编译（§12.4 B-02） |
| 新增 `device-ingest` | 新建 | EP-006 消费、EP-007/008 定时任务（含 **V1.2.0 新增的跨实例互斥锁 `CronLock`**，见 §5.2 G-001）、EP-012 配置加载 |
| 新增 `device-openapi` | 新建 | EP-001/002、验签与限流 |
| 新增 `device-admin` | 新建 | EP-003~005、EP-009~011、JWT 与操作人解析 |

### 12.2 外部依赖

| 外部服务 | 依赖类型 | 接口 / SDK | 联系人 | SLA |
|---------|---------|-----------|-------|-----|
| 外部生产者（`ASSET_SYS` 等） | MQ 上游 | Topic `device.info.change`，契约见 §5.2 EP-006 | **待指定** | 待约定 |
| 外部调用方 | HTTP | `GET /api/v1/devices/{sn}`、`POST /api/v1/devices/batch` | **待指定** | 待约定 |
| Kafka 集群 | 中间件 | 自建 / 云托管 | 待指定 | 需 ≥ 本系统 |
| Redis | 中间件 | 缓存 + 幂等键 + 重建锁 | 待指定 | 可降级（读侧回源，见 §11.2 风险 3） |
| MySQL ≥ 8.0（`ext_json` 表达式默认值需 8.0.13+） | 存储 | 主档 + 流水 | 待指定 | 核心，不可降级 |

> 版本兜底：若目标实例 < 8.0.13，`device_info.ext_json` 改为允许 `NULL`（见 `data-model` §6.1 注记），其余 DDL 不受影响。

### 12.3 入口影响清单

| 受影响的现有入口 | 影响类型 | 变更说明 |
|----------------|---------|---------|
| 无 | — | 全新系统，无存量入口回归范围 |

### 12.4 第三方依赖登记与不引入清单（V1.3.0 新增，评审 B-02 / B-03）

**依赖治理修正（B-02：按 V1.2.0 的 POM 无法编译）**

`implementation-map.md` 的 `IM-CORE-007` 把 `GlobalExceptionHandler`（`@RestControllerAdvice`）、`TraceIdFilter`（`OncePerRequestFilter`）、`ApiEnvelope`、`ErrorCode`、`JacksonConfig` 五个类放在 `device-core` 的 `web` 包下，而 `IM-BUILD-001` 给 `device-core` 的依赖清单里显式写了"**不引** `spring-boot-starter-web`"。两者直接矛盾：core 编译期没有 `spring-web`/`spring-webmvc`/`jakarta.servlet` 类型，这五个类**一个都编不过**。

| 处置 | 内容 | 理由 |
|------|------|------|
| 采用 | `device-core` 以 **`provided` 作用域**引入 `spring-boot-starter-web`，Web 相关类留在 core | 三类响应契约件（外壳、错误码、异常处理）被 openapi 与 admin **两个**进程共用，放 core 是唯一不重复的位置；`provided` 让 core 不把 Web 强加给 `device-ingest`（ingest 无 MVC 需求，引入 starter-web 会误启内嵌容器） |
| 否决 | 把这些类下放到 openapi/admin 各一份 | 会出现两份错误码枚举与两份外壳装配，§9.6 的"一处登记"不变式立刻失效，且 TC-API-03 的契约漂移断言无法覆盖 |
| 否决 | 新建第 5 个模块 `device-web-common` | 需求未给分层要求，为 5 个类开一个模块属过度设计；`provided` 已解决传递问题 |

**本期不引入清单**

| 组件 | 为何不引入 | 现有替代防线 | 触发引入的条件 |
|------|-----------|-------------|---------------|
| **Resilience4j / Sentinel**（熔断） | 本仓库 `java_springboot_specification.md` §九对熔断只列**【推荐】**，对"每个出站调用必须有连接 + 读/响应超时"才是**【强制】**。V1.2.0 把 `503/5030` 写成"已熔断"却没有任何组件与配置键承接，属把目标态写成现状 | §11.1 超时键 + Hikari/Redis 有界连接池 + `X-RateLimit-*` 限流 + 读侧有界自旋（`device.cache.rebuild-spin-times`）+ `@CircuitBreaker` 语义改为 fail-fast | 出现"依赖恢复期反复打穿 DB"的实测证据，或需要 bulkhead/降级回调的统一编排 |
| **Canal / Debezium**（binlog 订阅） | 数据变更源是外部 Kafka 消息，本系统不是主写方，无需反向同步 | 写侧删缓存 + 增量对账 EP-007 | 出现第三个直连 DB 的写入方 |
| **ShedLock / Quartz**（分布式调度） | 只有 2 个定时任务、周期固定，自建 Redis `SETNX` 锁足够；引入调度框架会带来额外的表或依赖 | `CronLock` + `device:lock:cron:{job}`（§5.2 G-001） | 任务数 > 5 或需要错过补偿/日历调度语义 |
| **Elasticsearch** | 见 `data-model` §10.2：多一个会不一致的副本，且本期定位是"外部镜像 + 读加速" | MySQL + 强制筛选条件（禁左模糊、强制时间范围、深翻页上限） | 后台多条件筛选成为高频诉求（`data-model` §9.5） |
| **分库分表中间件** | 见 `data-model` §10.1：会拆掉"幂等表与主档同库同事务"这条核心防线 | 单库 + 流水按月 RANGE 分区 | 主档超 `${device.scale-threshold-rows:5000000}` 行（`data-model` §9.5） |
| **Spring ProblemDetail** | 与 §9.6 统一外壳是两套对外契约，调用方无法同时解析 | 附录 C 已登记该规范冲突及裁决 | 需求方改口径（附录 E #9） |

**密钥与凭据来源（评审安全项，此前只在映射里出现、设计文档未登记）**

| 凭据 | 来源 | 禁止 | 轮换 |
|------|------|------|------|
| 外部调用方 AppKey Secret（HMAC 验签，§5.2 EP-001/002） | 挂载文件 `${device.openapi.app-secret-path}`（**无默认值，缺键即启动失败**），由 `AppKeyRegistry#secretOf(appKey)`（`implementation-map.md` 的 `device-openapi/.../security/AppKeyRegistry.java`）加载，文件内为 `appKey → [当前 secret, 上一版 secret]` 的列表结构 | ❌ 禁止写进 `application.yml` 明文、禁止进日志、禁止进代码常量（`java_springboot_specification.md` §密钥外部化【强制】） | **双 secret 并存即轮换窗口**：新旧各校验一次、任一命中即通过；轮换完成后把上一版置空。若只支持单 secret，轮换当天调用方会全量 `401/4010` |
| JWT 验签公钥（EP-003~011 后台） | 挂载文件 `device.admin.jwt.public-key-path` | ❌ 禁止把私钥放到本系统；禁止从 token 头 `alg` 反推算法 | 公钥支持按 `kid` 选择多把，轮换由外部 IdP 掌握 |
| DB / Redis / Kafka 口令 | 部署侧环境变量或密钥挂载 | ❌ 禁止 yaml 明文、禁止打印 | 由运维掌握，本系统只要求"改后重启即生效" |

> 这三行是本期唯一涉及"秘密"的面。共同约束一句话：**代码仓库里不出现任何可用凭据，日志里不出现任何可用凭据**（`java_logging_specification.md` §敏感信息【强制】）。AppKey Secret 与 JWT 公钥路径都必须走外部注入，否则 §5.2 的验签与鉴权设计只是纸面能力。


---

## 13. 测试要点

> 按入口组织，覆盖正常路径、边界与异常。**EP-006 的用例是本项目的主战场**：幂等、事务原子性、回滚释放、乐观锁四条必须做成集成测试，不接受纯单测覆盖。
>
> **测试设施（Spring Boot 规范【强制】）**：集成测试用 Testcontainers 起**真实 MySQL 8.0 与 Kafka**（`@ServiceConnection`），**禁止用 H2 代替**——本设计的分区表、`ON UPDATE CURRENT_TIMESTAMP`、JSON 表达式默认值、唯一索引冲突行为在 H2 上语义不同，用 H2 会得到"测试通过但生产不成立"的假信号。Web 层用 `@WebMvcTest` 切片，`@SpringBootTest` 只留给跨组件链路。

### EP-006 设备变更消费

| 用例ID | 场景 | 前置条件 | 输入 | 预期结果 | 关联 |
|-------|------|---------|-----|---------|------|
| TC-006-01 | 正常：在线状态变更 | 主档存在、source 已授权 | `ONLINE_CHANGE` | `online_status` 更新、`version+1`、幂等记录成功、流水 `result=1`、缓存 key 被删、offset 提交 | FR-001,002,003,005 |
| TC-006-02 | 幂等：同 `msg_id` 重复投递 | TC-006-01 已执行 | 同消息重投 | 主档不变、无新流水、**ack 且不重投** | FR-002 |
| TC-006-03 | 幂等：跨源同 `msg_id` | 两个 source 用同一 `msg_id` | 两条消息 | **两条都正常处理**（验证唯一键含 `source`，防静默丢弃） | FR-001,002 |
| TC-006-04 | 并发：同 SN 两维度同时更新 | 预置 `version=0` | 两线程各写不同维度 | 一个成功、一个重试后成功；**两个维度最终值都正确**（验证 R4 + R10） | FR-003 |
| TC-006-05 | 冲突回滚不吞消息 | 外部先改 `version` 制造 CAS 失败 | 单条消息 | `mq:dedup:*` 被释放 → 重试成功 → 数据落地（**专门验证 R3**） | FR-002,003 |
| TC-006-06 | 事务原子性 | 在流水写入后注入异常 | 单条消息 | **幂等记录与主档与流水全部回滚**，无脏留痕（R1 + R9） | FR-006 |
| TC-006-07 | 主档不存在 | 主档无该 SN | 单条消息 | 流水 `result=3`、主档未变、**事务提交**、消息 ack | FR-001 |
| TC-006-08 | 未授权来源 | `source=UNKNOWN_SYS` | 单条消息 | 主档不变、`UNAUTHORIZED_SOURCE` 计数 +1、ack 不重试 | FR-001 |
| TC-006-09 | 未知 `msg_type` | — | `FOO_CHANGE` | WARN + 计数 + ack，**后续消息不积压**（R11） | FR-001 |
| TC-006-10 | 重试超限进死信 | CAS 持续失败 | 单条消息 | `mq_dead_letter(status=0)` 落库 + 告警 + offset 提交（R13） | FR-001 |
| TC-006-11 | 死信重放 | TC-006-10 后修正授权/数据 | EP-011 重放 | 复用同一 Applier 成功落地、死信 `status=1` | FR-001 |
| TC-006-12 | 逻辑删后外部重推 | 设备 `deleted=1` | 正常变更消息 | 维度列被更新并留痕，`deleted` 不被消费端修改（R6） | FR-001,006 |
| TC-006-13 | **乱序行为基线** | 预置较新值 | 构造"旧事件"消息 | **断言当前会被覆盖**，把缺口固化为回归用例；外部补 `event_time` 后此用例改为断言不覆盖 | TD-004 |
| TC-006-14 | 消息体超 256KB | — | 超限消息 | 直接落死信，不尝试处理 | MQ §1.3 |
| TC-006-15 | Redis 不可用 | 关停 Redis | 正常消息 | 幂等退化到 DB 唯一索引，**数据仍正确落地**（TD-002） | FR-002 |

### EP-001 / EP-002 读侧

| 用例ID | 场景 | 输入 | 预期结果 |
|-------|------|-----|---------|
| TC-001-01 | 缓存命中 | 已缓存 SN | 200；响应不含 `maintain_*`/`ext_json`/`update_time`；**不产生 SQL** |
| TC-001-02 | miss 回源 | 清缓存后查询 | 回源 → 回填带抖动 TTL → 数据正确 |
| TC-001-03 | 穿透防护 | 不存在 SN 连续查 2 次 | 第 1 次回源并写 `__EMPTY__`；第 2 次**不回源**；404/4040 |
| TC-001-04 | 已逻辑删设备 | `deleted=1` | 视为不存在 404/4040；缓存被删或写哨兵 |
| TC-001-05 | 击穿 | 20 线程同查同一 miss key | 仅 1 次 DB 回源 |
| TC-001-06 | 哨兵时间转换 | `produce_time = 1970-01-01 00:00:00` | 响应 `produce_time: null`，不外泄哨兵值 |
| TC-001-07 | 验签与限流 | 错签名 / 过期时间戳 / 超配额 | 401/4010、401/4011、429/4290 + `Retry-After` |
| TC-001-08 | 时间格式 | 任意时间字段 | ISO 8601 带时区（HTTP 规范 §4.3） |
| TC-002-01 | 批量部分命中 | 含不存在 SN | 200；`items[].found` 与 `missing` 正确；**SQL 仅 1 条 IN** |
| TC-002-02 | 批量超上限 | 上限 + 1 条 | 400/4003，不拆分 |
| TC-002-03 | 批量 MGET | 100 条 | Redis 命令数 = 1 次 MGET + 回填 Pipeline，**无循环 GET** |

### EP-003~EP-011 后台与任务

| 用例ID | 场景 | 预期结果 |
|-------|------|---------|
| TC-004-01 | 新增已存在 SN | 409/4090 |
| TC-004-02 | 无 `Idempotency-Key` 并发双提交 | 只落一条（HTTP 规范 §9.1） |
| TC-005-01 | 改 PROFILE | 仅 PROFILE + `maintain_*` 列变化，其他维度列不受影响；缓存被删 |
| TC-005-02 | `version` 过期 | 409/4091，**无静默重试**，主档不变 |
| TC-005-03 | 后台尝试写 ONLINE 维度 | 403/4031（授权表未放开） |
| TC-009-01 | 逻辑删 | `deleted=1` + 流水 + 缓存删；后续查询 404 |
| TC-003-01 | 无条件列表查询 | 400/4004（强制筛选条件） |
| TC-003-02 | `sn_prefix` 右模糊 | 走 `uk_sn` 前缀；**禁止 `%kw%`** |
| TC-007-01 | 对账修复 | 人为"写库不删缓存"造脏 → 对账发现并删 key → 下次读到新值 |
| TC-007-02 | 对账幂等 | 连续两轮结果一致，无额外 DB 写 |
| TC-008-01 | 归档 | 分批 DELETE 每批 ≤ 1000；分区表 `DROP PARTITION` 秒级完成 |
| TC-007-03 | **定时任务跨实例互斥（V1.2.0 新增，验 G-001）** | 两个实例同一时刻触发 EP-007 → 只有一个进入任务体，另一个记跳过计数；持锁实例崩溃后 TTL 过期，下一轮另一实例可抢到 |
| TC-008-02 | **误删保护（V1.2.0 新增）** | `pmax` 非空时本轮不执行 `DROP PARTITION` 并告警；`retention-days=0` 或 `batch-size≤0` 时任务拒绝执行且 0 行被删 |
| TC-011-01 | 死信列表与重放 | 可分页查、重放后 `status=1`、数据落地 |
| TC-011-02 | 重放遇幂等冲突 | 原 `msg_id` 已成功消费过 → 重放返回 `409/4092`，**数据不被二次改写**，死信保持已处理 |
| TC-004-03 | 新建设备前的 `__EMPTY__` 残留 | 先查不存在的 SN（写哨兵）→ EP-004 建该 SN → **立即再查必须 200**（验证 EP-004 删哨兵） |
| TC-004-04 | EP-004 入参越权 | 请求体带 `online_status` / `remark` 外的状态列 → `400/4002`（不接受入参）或 `403/4031`（维度未授权），**不得静默忽略** |
| TC-SEC-01 | 动态列注入面（R14） | 消息 `data` 含 ``"online_status=1,version=0--"`` 之类的 key → 校验阶段整体拒绝、无 SQL 执行、`mq_dead_letter` 或计数留痕 |
| TC-SEC-02 | 密钥与凭据面（V1.3.0 新增，对应 §12.4） | 三条断言：① 全仓库（含 `application*.yml`、测试资源、日志配置）扫描不到 AppKey Secret 与 DB/Redis/Kafka 口令明文；② 日志与响应体中不出现 secret、`X-Signature`、JWT 原文（只允许打 `app_key` 与掩码）；③ 同一 `app_key` 配置新旧双 secret 时，用旧 secret 签的请求在轮换窗口内仍通过、窗口结束后被拒 |
| TC-SEC-03 | JWT 算法混淆（V1.3.0 新增，对应 §5.2 后台认证） | 构造 `alg: none` 与 `alg: HS256`（用公钥当 HMAC secret 签）两种 token → 均被拒且**不得**进入业务方法；`iss`/`aud` 不匹配与 `exp` 过期也各自被拒。错误码按 §9.6 既有分配，不新增码：验签/算法类失败为 `401/4010`，时效类失败为 `401/4011` |
| TC-OPS-01 | 优雅停机（R15） | 消费途中发 SIGTERM → 在途消息到终态且位点正确；重启后无重复副作用、无丢失 |
| TC-OPS-02 | 读侧依赖超时（V1.3.0 新增，对应 §11.1、§9.6 的 `503/5030`） | 用 Testcontainers 网络延迟/`iptables` 丢包把 Redis 打成超时 → EP-001 走回源且**不回填**；同时把 DB 也打成超时 → 返回 `503/5030`，**不得**出现 `500/5000`，响应仍带 `trace_id` 且与 `X-Trace-Id` 同值；日志为 WARN 级、无堆栈刷屏 |
| TC-OPS-03 | 定时任务时长超锁 TTL（V1.3.0 新增，对应 §5.2 G-001 不变式） | 人为把 EP-008 单轮数据量放大到预计时长 > `TTL × (1 - 0.2)` → 任务在剩余锁时长低于 `${device.cron.lock-safety-margin-ratio:0.2}` × TTL 时**主动截断本轮**并留下轮续跑标记；另一实例在锁仍有效期间必须始终抢不到锁；`cron.run.duration` 指标越过 `TTL × 0.8` 触发告警 |
| TC-API-01 | 响应外壳与头 | 任一 200/4xx 响应含 `trace_id` 且与响应头 `X-Trace-Id` 同值；对外查询带 `Cache-Control: no-store`；限流带 `X-RateLimit-*` |
| TC-API-02 | 分页口径 | EP-003/EP-010 参数为 `page/page_size`，响应含 `items/total/page/page_size`；深翻页超上限 → `400/4002` |
| TC-API-03 | 契约与实现漂移（**CI 门禁，非人工核对**） | 三条断言：① 契约 `components.schemas.Device` 的 `properties` 键集合 == `data-model` §7.1 标注"对外白名单"的列集合（当前 15 列）；② 契约 `Error.code` enum == §9.6 错误码表出现过的码集合；③ SpringDoc 运行时导出的 OpenAPI 与仓库 `contracts/api-contract.yaml` 做结构化 diff，路径/方法/状态码三项不一致即构建失败。任一不通过 → 阻断合并 |
| TC-API-04 | 错误码可达性 | §9.6 表中每个码都有对应触发路径，禁"表里有码但无入口能返回"（`4050` 由框架方法不匹配产生、`5030` 由依赖超时/不可用产生，需分别构造）；反之实现里出现的码必须已在表中登记 |

### 性能与容量验证（P2）

| 项 | 方法 | 通过标准 |
|----|------|---------|
| 消费吞吐 | 灌入 `10 × 假设峰值` 消息 | 无积压增长；lag 稳定 |
| 读侧命中路径 P99 | JMH/Gatling 压 EP-001 | ≤ `${device.openapi.p99-ms}` |
| 乐观锁冲突率 | 并发同 SN 混合维度刷数 | 冲突率与重试耗时在阈值内，无消息丢失 |
| 大 Key | `redis-cli --bigkeys` 抽样 | 无 > 10KB Value |
| 慢查询 | 开启慢日志跑一轮全入口 | 无全表扫描；`EXPLAIN` 命中预期索引 |

---

## 14. 风险评估

| 风险ID | 风险描述 | 发生概率 | 影响程度 | 综合等级 | 应对策略 | 负责人 |
|-------|---------|---------|---------|---------|---------|-------|
| R-001 | **外部消息缺 `event_time`**（违反 MQ 规范 §1.3【强制】），无法防业务乱序 | 高（已确认缺） | 高（旧值可覆盖新值） | 🔴 | ①需求方向外部提契约补齐；②本期以流水 + 人工纠正兜底；③TC-006-13 固化基线；④补齐后启用 TD-004 方案 A，**不改表结构** | 需求方 / 待指定 |
| R-002 | 无 PRD，需求边界仅来自会话口述，存在范围漂移与验收无依据 | 高 | 中 | 🟠 | 以 §3"不做清单"为验收边界；补 PRD 后升次版本重建 FR 映射 | 需求方 |
| R-003 | 量级与 SLA 未确认，§11 全为假设值 | 高 | 中 | 🟠 | 上线前压测重算；分区数、连接池、TTL、保留期均下发为配置项 | 待指定 |
| R-004 | 报废/回收后仍可被改写（无生命周期校验） | 中 | 中 | 🟡 | 需求方裁决不做；如需，扩展 `DimAuthGuard` 一处前置规则 | 需求方 |
| R-005 | 维度权威源漏配 → 新外部源消息被静默拒 | 中 | 高 | 🟠 | `UNAUTHORIZED_SOURCE` 计数告警 + 接入 checklist 增加"先配授权"；配置表写入需留操作人 | 待指定 |
| R-006 | 未产出技术调研报告（tech-generate 规范要求） | 高 | 低 | 🟡 | 由 `tech-research` 补 `research/tech-research-*.md`，重点核 Kafka 扩分区影响与 Redis 幂等键容量 | AI Agent / 需求方 |
| R-007 | Redis 故障期间对外 QPS 全部压到 DB | 低 | 高 | 🟠 | 对外限流 + 连接池上限 + **依赖超时后 fail-fast（`503/5030`）+ 有界自旋降级**（V1.3.0：本期不引入熔断框架，见 §12.4） + 故障演练（TC-006-15 已覆盖写侧退化，TC-OPS-02 覆盖读侧） | 待指定 |
| R-008 | 缓存脏读窗口对外未声明，调用方按强一致假设使用 | 中 | 低 | 🟡 | 接口文档写明"最终一致，窗口 ≤ 对账周期"；必要时响应带数据新鲜度头 | 待指定 |
| R-009 | ~~`api-contract.yaml` 未产出，前后端对接缺正式契约~~ **已处置（V1.1.0）**：`contracts/api-contract.yaml` 已产出并通过 schema 校验与语义 lint | — | — | ✅ 已关闭 | 剩余风险转为**契约漂移**：文档（§5.2/§9.6）与 YAML 双份维护。约束：改字段必须同一提交内改两处，且以契约为准回写本文档；漂移由 §13 TC-API-03 的 CI 断言拦截 | AI Agent |
| **R-010** | **设计未锁定即已产出实现映射**（V1.3.0 新增，评审 P0 派生风险）：`tech-review` 结论为**不通过、未锁定**（B-01 宪法前置未过 + 第 2 轮总分 7.6 < 8.0），但 `implementation-map.md` 已存在并被当作编码依据 | 高（当前状态即如此） | 高（映射按未评审通过的设计落点，一旦宪法/量级/外部契约变更，代码落点与阈值要返工） | 🟠 | ①**映射 frontmatter 的 `tech_design` 继续指向 `tech-design.md`**，不改成不存在的 `tech_design_final.lock.md`——指真文件比指"应有的文件"安全；②编码只允许先做**不受四项外部输入影响**的部分（Web 外壳/错误码/Jackson/trace_id、Mapper 与 CAS、幂等表读写、分区 DDL 结构），涉及授权真实 `source` 编码、限流配额、压测阈值的部分挂起；③解锁需要下列**四项人工/外部输入**，任一到位后升次版本重跑评审：<br> A. **项目宪法**：`.aisdd/Constitution.md` 由需求方确认（或明确书面豁免 `tech-review` Step 0 并留痕）——只有需求方能决定，AI 不得代拟生效。**注**：本轮分数未过线还叠加了技能权重求和 = 0.90 的缺陷（评审记录 S-01）；按归一化口径第 2 轮已得 8.444，故**宪法到位后重跑，通过的概率很高**——不要把"7.6 分"当成设计质量不足的信号；<br> B. **PRD 或等价的需求基线**：用于自拟 FR-001~006 的验收依据（§1.1 偏差声明）；<br> C. **量级与 SLA 实测**：§11 全部为假设值，需压测重算后固化连接池/TTL/分区数/`qps-per-app`；<br> D. **外部契约两项**：`device_dim_source_auth` 的真实 `source` 编码（`data-model` §9.2 步 6 发布门禁）与消息体 `event_time`（R-001 防线的升级前提） | AI Agent / 需求方 |

---

## 附录

### 附录 A：关键术语表

| 术语 | 说明 | 来源 |
|------|------|------|
| 维度（Dim） | 一组只能由同一权威源整体改写的列：`FACTORY / ONLINE / BIND / RECOVER / PROFILE / MAINTAIN` | 技术设计 §7.2 |
| 维度所有权 | `(dim_type, source)` 授权关系，决定某源能否写某维度 | TD-004 |
| 幂等两层 | Redis `SETNX`（挡并发）+ `mq_consume_record` 唯一索引（保正确性） | TD-002 |
| 分维度动态列更新 | 一条消息只 `UPDATE` 它负责的列，禁止全字段覆盖 | §9.3 R10 |
| 哨兵时间 | `1970-01-01 00:00:00` 表示"该时间未同步"，出口转 `null` | MySQL 规范 §1.1 NOT NULL 约束的产物 |
| Cache-Aside | 读：缓存→回源→回填；写：先库后删缓存 | Redis 规范 §2.1 |
| 空值哨兵 | `__EMPTY__`，缓存"确认不存在"以防穿透 | Redis 规范 §2.4 |
| 对账 | 按 `update_time` 增量比对 DB 与缓存并删脏 key | EP-007 |
| At-Least-One | Kafka 投递语义：重复是常态，必须幂等 | MQ 规范 §3.1 |
| 镜像层 | 本系统的定位：不产生业务事实，只准确落地外部事实并加速查询 | §6.2 |

### 附录 B：状态与结果编码定义

| 编码列 | 值 | 含义 |
|--------|----|----|
| `device_info.register_status` | 0 / 1 | 未注册 / 已注册 |
| `device_info.online_status` | 0 / 1 / 2 | 未知 / 在线 / 离线 |
| `device_info.bind_status` | 0 / 1 | 未绑定 / 已绑定 |
| `device_info.recover_status` | 0 / 1 / 2 | 正常 / 已回收 / 已报废 |
| `device_info.deleted` | 0 / 1 | 正常 / 逻辑删除 |
| `device_info_change_record.result` | 1 / 3 / 4 | 已更新 / 设备不存在 / 未授权来源 |
| `device_info_change_record.dim_type` | `FACTORY`/`ONLINE`/`BIND`/`RECOVER`/`PROFILE`/`MAINTAIN` | 维度编码 |
| `mq_consume_record.status` | 1 / 2 / 3 | 成功 / 失败 / 已进入死信 |
| `mq_dead_letter.status` | 0 / 1 / 2 | 待处理 / 已重放 / 已丢弃 |
| 消息 `msg_type` | `FACTORY_REGISTER`/`ONLINE_CHANGE`/`BIND_CHANGE`/`RECOVER_CHANGE`/`PROFILE_CHANGE` | → 维度映射见 `data-model` §8.4 |

> **`result` 编码故意不含"重复"与"版本冲突"**：重复消息在唯一键冲突时整事务回滚、不产生流水；版本冲突触发回滚重试，失败才落死信。这两类事实分别由 `mq_consume_record` 与死信表 / 监控指标承载。**同一事件不在两张表重复记两种口径**，否则排障时两处结论互相矛盾。

### 附录 C：规范符合性与冲突裁决

| 规范条款 | 要求 | 本文档处置 |
|---------|------|-----------|
| MySQL §1.1 | 表必备 `id / create_time / update_time` | ✅ 全部表遵循（早期会话草案的 `gmt_create/gmt_modified` 已按本仓库规范改为 `create_time/update_time`） |
| MySQL §1.1 | 字段尽量 NOT NULL + 默认值 | ✅ 业务时间用哨兵值（附录 A） |
| MySQL §1.1 vs MQ §4.1 示例 DDL | 示例表使用 `created_at/updated_at`，与 MySQL §1.1 冲突 | ⚠️ **规范内部冲突**：以 MySQL §1.1【强制】为准，统一 `create_time/update_time` |
| MySQL §1.2 | 唯一业务字段必须建唯一索引 | ✅ `uk_sn` / `uk_source_msg` / `uk_dim_source` |
| MySQL §1.2 | 组合索引高区分度列在最左 | ✅ `idx_model_code_produce_time`；⚠️ MQ §4.4【强制】`(status, created_at)` 属"常量等值 + 时间范围"扫描，按 MQ 规范保持 `status` 在左 |
| MySQL §1.2 | VARCHAR 索引需指定长度 | ✅ `sn` VARCHAR(64)、`bind_target` 前缀 32（见 `data-model` §8） |
| MySQL §1.2 | 禁左模糊/全模糊 | ✅ 后台仅右模糊与等值 |
| Redis §1.2 | Key `业务域:模块:实体:ID` + 必设 TTL | ✅ §9.4 |
| Redis §2.2 | 先写库后删缓存 | ✅ §9.4 + R8 |
| Redis §2.4 | 穿透/击穿/雪崩防护 | ✅ §9.4 |
| MQ §1.2 | Topic `业务域.模块.事件类型` | ✅ `device.info.change` |
| MQ §1.3 | 消息体必须含 `event_time` | ❌ **外部契约不满足**，见 R-001（本文档如实标注为违规项而非绕过） |
| MQ §3.1 | 消费幂等（唯一索引 / Redis SetNX） | ✅ TD-002；⚠️ 与示例差异：`SETNX` 键在回滚路径必须释放（R3） |
| MQ §3.2 | 消费内禁长耗时操作 | ✅ 事务仅 4 条 SQL；对账/归档独立任务 |
| MQ §3.3 | 死信可查询、可重放、有管理入口 | ✅ EP-011 + `mq_dead_letter` |
| MQ §4.4 | 幂等表 `uk_msg_id`【强制】 | ⚠️ **偏离并说明**：改为 `uk_source_msg(source, msg_id)`。理由：`msg_id` 由外部生产，不能假设跨源全局唯一；仅用 `uk_msg_id` 会让后接入同源 id 消息**被误判重复而静默丢弃**。若外部确认全局 UUID，可退回 `uk_msg_id` |
| MQ §5.1 | DB 与消息强一致用本地事务表 | ✅ 本项目为纯消费方，等价实现为"幂等表 + 主档同事务"（R1） |
| HTTP §1.1 | URL 复数名词 | ✅ `/api/v1/devices` |
| HTTP §4.1 | 统一 `{code,message,data}` | ✅ §9.6 |
| HTTP §4.2 | HTTP status 表传输层、`code` 表业务层 | ✅ 本仓库口径；与"恒 HTTP 200 + 业务 code"的其他项目口径不同，**以本仓库规范为准** |
| HTTP §4.3 | 时间返回 ISO 8601 字符串 | ✅ API 层 `2026-10-29T10:21:05+08:00`；DB 存本地 `DATETIME`，序列化层负责时区 |
| HTTP §5.1 | 错误响应含 `trace_id` | ✅ §9.6 |
| HTTP §9.1 | 写接口幂等键 | ✅ EP-004 `Idempotency-Key`；EP-005/009 以 `version` 条件更新实现自然幂等 |
| HTTP §4.2 | 示例表把 `4090` 配在 HTTP 400 行，而同条规则要求"code 前 3 位对齐 HTTP 状态码" | ⚠️ **规范内部冲突**：按规则本身裁决，`4090/4091` 归入 HTTP **409**（§9.6）。这是本节唯一一处示例与规则自相矛盾的地方 |
| HTTP §4.1 + §5.1 vs Spring Boot §"响应契约"【强制】 | Boot 规范要求**错误体统一用 `ProblemDetail`（RFC 7807）**；HTTP 规范要求**所有 JSON 响应统一 `{code,message,data}` 且错误含 `trace_id`** | ⚠️ **两份仓库规范的【强制】直接冲突**，本设计按 HTTP 规范执行（理由：HTTP 规范专门定义对外契约、且调用方按 `code` 前 3 位分类处理）。落地方式：错误仍集中在 `@RestControllerAdvice` 产出（满足 Boot 规范"集中处理"的实质），但响应体字段用 `{code,message,data,trace_id}`。**该裁决需架构负责人确认**（附录 E #9） |
| HTTP §6.1 | offset 分页正例为 `page` + `page_size`，响应 `{items,total,page,page_size}` | ✅ 后台列表/流水接口按此命名（原稿写 `size`，已改） |
| HTTP §10.1 | 限流响应头 `X-RateLimit-Limit/Remaining/Reset` + `Retry-After` | ✅ EP-001/002 全部返回（原稿仅 `Retry-After`，已补） |
| HTTP §10.2 | 【推荐】GET 启用 `Cache-Control`/`ETag` | ⚠️ **有意不启用**：本系统数据为最终一致（脏读窗口 ≤ 对账周期），再叠一层 HTTP/CDN 缓存会把窗口相加且不可观测。对外统一返回 `Cache-Control: no-store`，新鲜度由服务端 Redis 层控制（§9.4） |
| HTTP §14 | 【推荐】`/health`、`/ready`、`/metrics` | ✅ 以 EP-013 交付，实现为 Actuator 探针（非自研接口） |
| HTTP §3.1 / §3.2 | 【强制】禁止一律返回 200，须用精确状态码 | ✅ `201`（EP-004）/`204`（EP-009，无体）/`405`/`409`/`429`/`503` 全部入表（§9.6 与契约 `responses` 一一对应）。原稿所有成功都用 200，属【强制】违规，已改 |
| HTTP §2.1 | GET 安全且幂等；POST 非幂等 | ⚠️ **有意偏离**：EP-002 用 `POST` 承载只读批量查询（100 个 SN 放不进 URL）。偏离的代价与补偿约束写在 §5.2 EP-002"方法语义裁决"行：无副作用、同 body 结果一致、计入同一配额 |
| HTTP §1.1 | URL 用复数名词、不含动词 | ✅ 路径全为复数名词（`devices`/`change-records`/`dead-letters`）；`/replay`、`/discard`、`/batch` 是**子资源名词**而非动词短语，且层级 ≤ 3 层（§1.2【推荐】） |
| HTTP §12 | 【强制】所有 API 必须提供 OpenAPI 3.0+ 文档 | ✅ `contracts/api-contract.yaml`（OpenAPI **3.1.0**，满足"3.0+"）。已通过 `swagger-cli validate`（schema 校验）与 `@redocly/cli lint --extends=minimal`（语义 lint）双校验。**注意**：规范只要求"有文档"，未要求生成 HTML 站点；`swagger-ui`/`redoc` 渲染属前端范围，本期不含（§10） |
| Spring Boot §迁移【强制】 | 所有环境用 **Flyway/Liquibase** 版本化迁移管理表结构，禁止 `ddl-auto=update` | ❌ **本期不满足，按"有意偏离 + 豁免留痕"登记**（V1.3.1 定稿，不再待裁决）。需求方 2026-10-29 裁决"DDL 不需要迁移"；附录 E #12 原登记的两种读法（(a) 不把 §6 的 DDL 落成 `.sql` / (b) 弃用 Flyway、上线时人工执行）**对代码的处置完全一致**——都不引 `flyway-core`、都不产出 `db/migration/*.sql`，故编码启动时不必先裁决。现状：生产表结构由运维按 `data-model` §6 人工执行（执行记录回写 `IM-DB-001`），应用启动路径不含任何 DDL；集成测试用 `src/test/resources/db/test-schema.sql` 夹具（与 §6 同源，仅测试用，不构成生产迁移产物）。偏离的代价与补偿：① 结构一致性从"迁移工具校验"改为"`information_schema` 与 §6 逐列比对的集成用例"（`IM-DB-001` Tests 第 1 行）；② 三进程"谁执行迁移"的问题被消解为"发布前 DDL 必须已执行"的前置检查项（§6.5 上线检查、`data-model` §9.2）。**若后续推翻本裁决改回版本化迁移，需同步改动本行、§12.1、`IM-BUILD-001` core 依赖行、`IM-DB-001` 全单元与附录 E #12 共五处。** |
| Spring Boot §Actuator【强制】 | 独立 management 端口、显式白名单端点、liveness 禁含外部依赖检查 | ✅ EP-013 约束：liveness 不含 DB/Redis/Kafka；readiness 才检查依赖（否则依赖抖动会杀进程造成雪崩） |
| Spring Boot §优雅停机【强制】 | `server.shutdown=graceful` | ✅ 已升为写侧规则 R15（消费进程的停机顺序决定消息是否丢） |
| Spring Boot §外部调用重试【强制】 | 重试必须有界：最大次数 + 超时预算 + **带 jitter 的指数退避** | ⚠️ 原稿只写"指数退避"，缺 jitter 与总预算 → 已在 §11.1 补 `retry.backoff-multiplier` / `retry.jitter` / `retry.budget-ms` |
| Spring Boot §外部调用弹性 | **超时**为【强制】（每个出站调用必须有连接 + 读/响应超时）；**熔断器为【推荐】** | ✅ 超时：§11.1 新增"读侧依赖超时"行登记 Redis/MySQL/Hikari 四个键，TC-OPS-02 验证。<br>⚠️ 熔断：**本期不引入**，并按【推荐】的真实强度如实标注。V1.2.0 曾在 §9.6 把 `503/5030` 写成"依赖不可用且已熔断"、把"熔断降级预案"写进 §11.2 风险 3 与 R-007，却没有任何组件、配置键与代码落点承接——属把目标态写成现状，评审 B-03 判定后于 V1.3.0 全文改为"超时 + fail-fast"（§9.6、§11.1、§11.2、§12.4、§13）。若后续引入熔断框架，`5030` 的产生方式变更需同步改这五处与契约的 `description` |
| Spring Boot §配置命名【强制】 | 配置项规范名用 **kebab-case** | ⚠️ 原稿混用点分（`device.consume.record-retention-days`）→ 全文统一 kebab（本文与 `data-model` 同步修订） |
| Spring Boot §测试【强制】 | 集成测试用 Testcontainers 跑真实 DB/broker，**禁止 H2 代替生产库** | ✅ 已在 §13 测试设施约束中落实（本项目的幂等/CAS/分区行为在 H2 上测不出来） |
| MyBatis §SQL 注入【强制】 | 参数绑定用 `#{}`，禁止 `${}` 拼接 | ⚠️ **本设计与该条存在张力**：分维度动态列更新必须拼**列名**（标识符无法用 `#{}`）。已加 R14 约束列名只能来自代码白名单，值仍用 `#{}` |
| 日志 §敏感操作【强制】 | 增删改必须打印操作人、对象、**变更前后值**；批量操作打印总数与成败计数 | ✅ 新增 §9.7 日志要求（流水表留痕不能替代日志：日志用于 trace_id 关联排障，流水用于业务追溯） |
| tech §3.1 | 必须有 PRD 才能出技术设计 | ❌ 见 §1.1 偏差声明 |

### 附录 D：参考资料

- 本仓库规范：`.aisdd/rules/`（`mysql` / `redis` / `mq` / `http_restful` / `tech_specification` / `java_spring` / `java_middleware` / `java_springboot`）
- 模板：`.qoder/skills/tech-generate/templates/tech-design-template.md`、`data-model-template.md`
- 数据模型与 DDL：[data-model/data-model.md](data-model/data-model.md)
- 机器可读契约：[contracts/api-contract.yaml](contracts/api-contract.yaml)
- 实现映射：[implementation-map.md](implementation-map.md)
- **本项目评审产物（V1.3.0）**：[review_reports/tech_review_discussion.md](review_reports/tech_review_discussion.md)、[review_reports/tech_review.json](review_reports/tech_review.json)
- **缺失的上游约束**：`.aisdd/Constitution.md` 不存在 → `tech-review` Step 0 未通过，本文档**未锁定**（§1.1、§14 R-010）
- 需求输入：2026-10-29 需求方会话口述（无 PRD 文件）

### 附录 E：待确认项清单（不得当作已定事实）

| # | 待确认 | 影响 | 当前处置 |
|---|-------|------|---------|
| 1 | 外部能否补 `event_time` | TD-004 能否升级为防乱序 | 按"不能"设计，R-001 记录 |
| 2 | 设备总量与日均变更条数、对外查询 QPS | 分区数、TTL、保留期、容量、限流配额 | §11 全为假设值 + 公式 |
| 3 | 批量上限、缓存 TTL/抖动、空值缓存 TTL、对账周期、幂等键 TTL、维度授权刷新周期（`data-model` §7.5）、保留期与清理批量、分库分表触发阈值 | 性能与内存 | 全部下发为 `${配置项}`，附建议值（键名与默认值见 `data-model` §9.4、§10） |
| 4 | 各维度权威 `source` 清单 + 是否需要 `device_dim_source_auth` 的管理 API | 授权表初始化数据与运维方式 | 表已建；本期由人工 SQL 维护（§8 已标待办） |
| 5 | 后台可写维度范围（当前默认 PROFILE + MAINTAIN） | 是否允许后台直接纠错外部维度 | 按默认设计，配置可放开 |
| 6 | `device_info` 是否需要按 `sn` 预留分片键 | 未来分库分表迁移成本 | 本期不分表；`sn` 已可作分片键，主键仍自增 `id`（MySQL §1.1） |
| 7 | 前端范围 | §10 是否需展开 | 本期不含前端 |
| 8 | 外部消息最大重放/回溯窗口（故障后可能补推到多久以前的 `msg_id`） | 决定 `mq_consume_record` 保留期：清早了会把补推当新消息重复处理（不写错值，但污染流水与时间轴） | 保留期按下限 90 天设计，见 `data-model` §9.4；确认后可调 |
| 9 | **错误响应体形态**：Spring Boot 规范【强制】要求 `ProblemDetail`(RFC 7807)，HTTP 规范【强制】要求 `{code,message,data,trace_id}` | 对外契约与调用方解析方式，属**规范间冲突**，超出本设计可自决范围 | 本期按 HTTP 规范执行（附录 C 已记）；若架构侧要求 RFC 7807，契约与 §9.6 一起改，代码影响面仅 `@RestControllerAdvice` 一处 |
| 10 | ~~表结构迁移工具选型（Flyway vs Liquibase）与是否用 MyBatis（非 JPA）~~ → **V1.3.1 关闭**：工具选型问题不再存在 | Spring Boot 规范【强制】要求版本化迁移；该条示例语义基于 JPA（`ddl-auto`、OSIV） | 本项目用 MyBatis，JPA 专属条款不适用。**迁移工具本期不引入**（见 #12 已定稿），本行结论：Spring Boot §迁移【强制】= 有意偏离 + 豁免留痕，无选型待办 |
| 11 | EP-004 后台新增设备是否允许带工厂/生产时间 | 影响建档字段集合与 `register_status` 初值（当前固定 0 = 未注册） | 当前按"只允许 PROFILE 列"设计（§5.2）；若业务要手工补出厂信息，需放开 FACTORY 维度授权并复核覆盖风险 |
| **12** | **DDL 交付形态** → **V1.3.1 已定稿，关闭本待裁决项**（需求方 2026-10-29 裁决"DDL 不需要迁移"） | 决定 Spring Boot §迁移【强制】是"合规"还是"需豁免"；决定 `data-model` §9.2 上线步 1（三进程只有一个执行迁移）是否成立；决定 `device_dim_source_auth` 授权数据由谁写入 | 结论：原两种读法（(a) 不产出 `.sql` / (b) 弃用 Flyway 人工执行）**对代码的处置一致**，编码启动不需要先区分，因此本项从"待裁决"改为"已定稿"。落地：不引 `flyway-core`、不产出 `db/migration/*.sql`；`data-model` §6 的 DDL 仍是唯一事实来源，由运维人工执行（执行记录回写 `IM-DB-001`）；授权数据由人工脚本写入，占位 `source` 的发布门禁随之挂在人工脚本上；集成测试夹具 `src/test/resources/db/test-schema.sql` 与 §6 同源。**残余待办**（不阻塞编码）：人工执行形态需要运维确认执行窗口与实例清单，且 §9.2 步 1 的"只有一个进程执行迁移"表述已由 `data-model` 同步改述为前置检查项 |
| **13** | **后台细粒度角色矩阵**：谁能验签重放/丢弃死信、谁能改 PROFILE、谁能逻辑删除 | EP-003~011 目前只有"已认证 + 维度授权表"两层，缺"角色 → 可操作入口"映射；越权风险由认证层单挡 | 本期按"认证通过 + `device_dim_source_auth` 放行"设计（§5.2 后台认证注），不做角色矩阵；需要需求方给出角色清单后增补，落地面为 JWT claim 解析 + 一处拦截器 |
| **14** | **EP-010 流水查询是否限定时间范围** | 当前 `WHERE sn = ?` 不含分区键 ⇒ **无分区裁剪**，保留期内每月分区各一次索引探查（§9.1 已更正）；若需求方要求"只查最近 N 天"，必须显式带 `create_time` 范围才能触发裁剪 | 现状不加分区裁剪、靠单 SN 行数有界兜住；若需求方要求"只查最近 N 天"，必须把 `create_time` 范围做成**必填**入参才能触发裁剪，同时改契约与 §9.1 |
| **15** | **`503/5030` 是否只给 EP-001/EP-002**（V1.3.0 评审核实项） | 后台与死信接口的依赖故障语义；也决定 `TC-API-04` 的错误码可达性断言范围 | **维持并已机器核实**：契约的 `'503'` 响应只出现在 `/api/v1/devices/{sn}` 与 `/api/v1/devices/batch` 两个操作，与 §9.6"出现入口"列同串。理由：只有对外读接口承诺了降级路径（回源不回填），后台接口在依赖故障时宁可 `500/5000` 也不掩盖。若后续要求后台也返回 `503`，必须同时改 §9.6、契约两处与 `TC-API-04` |

---

**自检结论 A**（对照 `tech_specification.md` §7.2）：#1 PRD 依赖检查 ❌（无 PRD，已在 §1.1 声明并建自拟 FR）；#2 需求映射 ✅；#3 UI 关联 ✅（无前端，§10 已声明）；#4 版本记录 ✅；#5 规模决策 ✅（中型，6 功能点 / 4 模块）；#6 接口完整性 ✅（请求/响应/错误码齐全，且 `contracts/api-contract.yaml` 已产出并通过校验，HTTP 规范 §12【强制】满足；见 §1.2）；#7 异常处理 ✅（§9.3 R1~R15 + §11.2）；#8 影响范围 ✅；#9 引用完整性 ✅（`contracts/api-contract.yaml` 引用已落地为真实文件，`data-model` 章节号 §8.3/§8.4 均已存在；仓库规范文件名逐条核对存在）；#10 模板完整性 ✅（无空占位章节，待确认项集中在附录 E 且均给出当前处置）。

**自检结论 B**（V1.3.0 评审闭环，对照 `tech-review` SKILL 的 Step 0/6/8/9）：

| # | 检查项 | 结论 |
|---|--------|------|
| 1 | Step 0 宪法前置 | ❌ **未通过**。`.aisdd/Constitution.md` 不存在，技能原文要求"停止执行 / 终止当前 Skill"并输出 `❌ 未找到项目宪法文档 (.aisdd/Constitution.md)。请先执行 constitution skill 生成项目宪法后再继续。` 本轮按需求方显式指令（"跑 tech-review 下吧"）继续完成了评审，但**不因此绕过前置**：把 B-01 记为 P0 阻塞项，且因"宪法一票否决"，即使分数达标同样不得锁定 |
| 2 | Step 6 判定 | ❌ **不通过**：第 2 轮总分 **7.600**（按技能字面公式 `Σ(得分 × 权重)`，< 8.0）+ 存在阻塞项 → 不生成 `tech_design_final.lock.md`。产物 `review_reports/tech_review.json`（`status: rejected`）与 `review_reports/tech_review_discussion.md` 均已产出。<br>⚠️ **同时如实登记技能自身的评分缺陷 S-01**：SKILL 的 6 维权重求和 = **0.90** 而非 1.00，故按字面公式满分只有 9.000；若归一化，第 2 轮为 **8.444（已过线）**。本轮**不擅自归一化、不改技能定义**，按字面口径出分并同列归一化值。**结论不受该歧义影响**（B-01 宪法一票否决独立成立），但它意味着**剩余的真实阻塞只有宪法一项**——补齐后重跑，分数大概率也过 |
| 3 | Step 9 ADR 触发 | ⏭️ **未触发**：`adr-generate` 的条件是"锁定件成功生成（评审状态 approved）"，条件不成立，本轮不产出 ADR |
| 4 | React 循环次数 | 2 轮（< 5，未触发"人工介入"阈值；但因 B-01 属外部输入缺失，继续自修不会改变结论，故停在第 2 轮） |
| 5 | 虚报清扫（本轮删掉的"目标态当现状"） | 五处熔断类：§9.6"已熔断"、§11.2 风险 3"熔断预案"、R-007"熔断降级预案"、TC-API-04"`5030` 由熔断产生"、§9.1 EP-010"分区表天然裁剪"；两处改待裁决：§12.1 + 附录 C 的"表结构以 Flyway 版本化交付"；一处编译性缺陷：core 依赖清单缺 spring-web（§12.4 B-02） |
| 6 | 交叉一致性（V1.3.0 新增物在引用处同串） | ✅ 配置键 `device.cron.lock-safety-margin-ratio` 与 `device.openapi.app-secret-path`（G-002 表由 12 键增至 14 键；前者在 §5.2 G-001 不变式、§11.1 表、TC-OPS-03 三处同串，后者在 §12.4 密钥表、G-002 表两处同串）；用例 `TC-SEC-02`、`TC-SEC-03`、`TC-OPS-02`、`TC-OPS-03`（§13 ↔ §5.2/§11.1/§12.4/R-007 反向引用）；`R-010`（§1.2 ↔ §14）；待确认 `#12~#14`（附录 C / §12.1 / §9.1 / §5.2 各按其号引用）；契约 v1.2.1 **只改 `description`**，HTTP 面与字段集合未变 → §1.2 的 15 列与 `TC-API-03` 三条断言仍成立 |
