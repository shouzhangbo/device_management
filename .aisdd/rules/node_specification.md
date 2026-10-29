---
name: Node.js 后端开发规范
version: 1.0.0
description: Node.js 后端（Express/Koa/NestJS）开发规范，覆盖项目结构、清晰架构分层、禁止过度编程与过度抽象、每个函数中文注释、敏感操作（写库/Redis/Kafka/外部调用）与核心逻辑强制日志、异步与错误处理、不确定需求处理。为 AI Agent 生成 Node.js 代码提供正反例对照。约束等级：【强制】（必须遵守）/【推荐】（尽量遵守）。
---

# Node.js 后端开发规范

## 前言

本规范用于约束 AI Agent 生成 Node.js 后端代码的质量，核心约束包括：**清晰架构、避免过度编程、避免过度抽象、每个函数必须中文注释、敏感操作与核心逻辑必须打印日志**。约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。


## 一、项目结构与命名规范

**【强制】项目按业务功能模块组织，禁止按技术类型平铺（如全部 Controller 放一个目录、全部 Service 放一个目录）。**

```
src/
├── app.js                # 应用入口（装配中间件与路由）
├── config/               # 配置（按环境拆分，敏感配置走环境变量）
├── modules/              # 业务模块（按模块聚合）
│   ├── order/
│   │   ├── order.controller.js   # 接口层
│   │   ├── order.service.js      # 业务层
│   │   ├── order.repository.js   # 数据访问层
│   │   ├── order.validator.js    # 入参校验
│   │   └── order.model.js        # 数据模型/ORM 定义
│   └── user/
└── common/               # 公共工具（时间、脱敏、统一响应等）
```

**【强制】文件命名使用 `kebab-case`（如 `order.service.js`），类名使用 `PascalCase`，函数/变量使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。**


## 二、清晰架构

**【强制】代码必须保持清晰的分层结构，禁止跨层调用与逻辑堆叠。**

```
Controller（接口层） → Service（业务层） → Repository（数据访问层）
                          ↓
             外部服务/中间件（DB/Redis/Kafka/第三方 HTTP）
```

**反例：**
```javascript
// ❌ 错误：Controller 直接操作数据库、拼 SQL、做业务判断
router.get('/order/:id', async (req, res) => {
  const rows = await db.raw('SELECT * FROM t_order WHERE id = ?', [req.params.id]); // ❌ 接口层写 SQL
  if (!rows.length) return res.json({ code: 4004, msg: '订单不存在' });
  const total = rows[0].items.reduce((s, i) => s + i.price * i.count, 0); // ❌ 业务计算散落
  res.json({ code: 0, data: { id: rows[0].id, total } });
});
```

**正例：**
```javascript
// ✅ 正确：接口层只做参数接收与响应封装，业务与数据访问收敛到 Service/Repository
router.get('/order/:id', async (req, res, next) => {
  try {
    const order = await orderService.getOrderById(req.params.id); // 业务层
    res.json(Result.success(order));
  } catch (err) {
    next(err); // 统一异常处理
  }
});
```

**【强制】每个函数/模块职责单一，禁止一个函数同时承担"校验 + 业务 + 转换 + 持久化"全部职责。**

**【强制】禁止跨层调用（如 Controller 直接访问 Repository、Service 直接访问数据库连接），必须遵循约定的依赖方向。**


## 三、避免过度编程

**【强制规则】编码前先建需求清单，编码时只写"需求明确要求"的内容。**

> **后果**：违反本节约定的任何一条，视为**任务失败**，禁止提交；必须删除越界代码、重跑测试后再谈完成。

1. **【编码前建两栏清单】** 开始编码前，将任务需求拆分为两栏并逐条列出：
   - **需求明确要求（必须实现）**：如"创建订单""查询用户"
   - **需求未提及（禁止实现）**：缓存、重试、通知、埋点、统计、定时任务、格式校验、日志中间件、权限等
   只实现"明确要求"栏；"未提及"栏内容若确实必要，用 `// TODO[AI]:` 标注待确认，**不得直接实现**。
2. **【最小实现优先】** 先写满足验收的**最小可行实现**；任何额外能力必须能在任务描述或技术设计文档中找到原文依据，找不到即为越界。
3. **【新增依赖门槛】** 新增第三方依赖或中间件调用必须能在 PRD 或技术设计中找到依据；找不到 → 标 `// TODO[AI]:` 并停止，禁止自行引入。
4. **【一函数一职责】** 每个函数只做需求要求的一件事，禁止"顺手/顺便/加上更完善"式扩展。
5. **【代码量自检】** 函数超过 80 行，或单个函数出现 ≥2 个处理不同业务的 `if` 分支时，必须停下来自查是否混入了非需求逻辑。
6. **【越界即停止】** 一旦产生"加上这个更健壮/更完善"的念头，立即停止实现，改标 `// TODO[AI]:`，不得写进正式代码。

**反例：**
```javascript
// ❌ 错误：需求只要求"创建订单"，AI 自行补充了缓存、重试、埋点、定时任务等
async function createOrder(data) {
  const { userId, items } = validateAndTransform(data);
  await cache.set(`draft:${userId}`, data, 600);        // ❌ 需求未要求草稿缓存
  const order = await orderRepository.create(userId, items);
  await retryQueue.enqueue(() => notify(order));        // ❌ 需求未要求通知
  await stats.incr('order.create');                     // ❌ 需求未要求埋点
  scheduleJob(() => expireOrder(order.id));             // ❌ 需求未要求定时任务
  return order;
}
```

**正例：**
```javascript
// ✅ 正确：只实现需求要求的功能
async function createOrder(userId, items) {
  return orderRepository.create(userId, items);
}
```

**【强制】禁止引入项目未声明/未安装的依赖；优先使用 Node.js 内置能力或项目已有依赖。**

**反例：**
```javascript
// ❌ 错误：项目未安装 lodash，AI 自行使用
const _ = require('lodash');
const id = _.uniqueId();
```

**正例：**
```javascript
// ✅ 正确：使用内置能力
const id = crypto.randomUUID();
```


## 四、避免过度抽象

**【强制规则】能直接实现就不抽象；抽象必须满足"真实存在 ≥2 个实现/调用方"。**

> **后果**：违反本节约定的任何一条，视为**任务失败**，禁止提交。

1. **【单实现禁止抽象】** 一个抽象（基类、抽象方法、工厂、依赖注入容器）当前只有 **1 个实现**时，禁止创建抽象层，直接写实现。
2. **【两处真实复用才提取】** 公共代码只有在**同一需求内 ≥2 处真实使用**时才允许提取；仅 1 处使用禁止提取公共函数/工具类。
3. **【设计模式强门槛】** 设计模式（工厂/策略/模板方法/观察者/装饰器/Builder 等）只在需求或技术设计文档明确要求时使用；"提高扩展性/将来可能需要"**不构成**使用理由。
4. **【禁止自造分层】** 只允许使用项目既定分层（如 Controller/Service/Repository）；禁止在既定分层之外自创额外抽象层（如额外的 Facade、ApplicationService）。
5. **【简单模型先行】** 能用基础类型/普通对象/DTO 表达的，禁止引入类继承体系、泛型工厂等复杂建模。
6. **【不维护"以防万一"抽象】** 若演进后某抽象只剩 1 个实现，必须删除抽象直接实现，禁止"以防万一"保留。

**反例：**
```javascript
// ❌ 错误：需求只是一个简单查询，却引入抽象基类 + 工厂 + 泛化
class BaseService {
  constructor(repo) { this.repo = repo; }
  async get(id) { return this.repo.findById(id); }
}
class OrderService extends BaseService {}
class UserService extends BaseService {}
function createService(kind) { return kind === 'order' ? new OrderService(...) : new UserService(...); }
```

**正例：**
```javascript
// ✅ 正确：直接实现，不做过度抽象
async function getOrderById(id) {
  return orderRepository.findById(id);
}
```

**提交前自查（必须逐项通过）：**

- 需求原文出现过"缓存"吗？没有 → 删除所有缓存代码
- 需求原文出现过"通知/重试/埋点/统计/定时任务"吗？没有 → 删除对应代码
- 每个新增依赖都能指出它在需求或技术设计中的出处吗？指不出 → 删除
- 我创建的每个基类/抽象方法/工厂，当前有 ≥2 个真实实现吗？没有 → 删除抽象，直接实现
- 我提取的每个公共函数，有 ≥2 处真实调用吗？没有 → 撤销提取
- 我使用的每个设计模式，在需求/技术设计中有出处吗？没有 → 移除
- 删除越界代码/抽象后，是否已重跑单测且全部通过？


## 五、每个函数必须中文注释

**【强制】每个函数/方法必须编写中文 JSDoc 注释，说明函数职责、参数含义、返回值；核心业务逻辑、关键分支、边界条件必须补充中文行注释。**

> 无注释的函数即使逻辑正确，也视为未完成，禁止提交。

**反例：**
```javascript
// ❌ 错误：函数无注释，核心逻辑无说明
async function settle(order) {
  const total = order.items.reduce((s, i) => s + i.price * i.count, 0);
  await inventory.lock(order.items);
  await pay(order.userId, total);
}
```

**正例：**
```javascript
// ✅ 正确：中文 JSDoc + 核心逻辑中文注释
/**
 * 结算订单：锁定库存并完成扣款。
 * @param {Object} order - 待结算订单，必须已通过金额与库存校验。
 * @returns {Promise<PaymentResult>} 支付结果。
 * @throws {Error} 支付失败时抛出。
 */
async function settleOrder(order) {
  // 先锁库存再扣款，避免支付成功后出现超卖
  const total = order.items.reduce((s, i) => s + i.price * i.count, 0);
  await inventory.lock(order.items);
  return pay(order.userId, total);
}
```

**【强制】注释使用中文；禁止将代码逐行翻译成注释，注释应说明"为什么这样做"。**


## 六、敏感操作与核心逻辑日志

**【强制】所有敏感操作（写数据库、写 Redis、发 Kafka/消息、调用外部服务、文件写入、删除/更新数据）执行前后必须打印日志，记录操作对象、关键参数、操作结果；核心业务逻辑关键节点必须打印日志。**

**反例：**
```javascript
// ❌ 错误：写库、写缓存、发消息均无日志
async function markOrderPaid(orderId) {
  await orderRepository.updateStatus(orderId, 'PAID');
  await redis.del(`order:${orderId}`);
  await kafka.send('order_paid_topic', { orderId });
}
```

**正例：**
```javascript
// ✅ 正确：敏感操作前后均有日志
async function markOrderPaid(orderId, operator) {
  logger.info('标记订单已支付开始', { orderId, operator });
  logger.info('更新订单状态', { orderId, before: 'PENDING', after: 'PAID' });
  await orderRepository.updateStatus(orderId, 'PAID');
  logger.info('清除订单缓存', { orderId });
  await redis.del(`order:${orderId}`);
  logger.info('发送订单已支付消息', { orderId, topic: 'order_paid_topic' });
  await kafka.send('order_paid_topic', { orderId });
  logger.info('标记订单已支付完成', { orderId });
}
```

**【强制】应用必须配置统一日志框架（如 pino/winston），输出到文件，禁止使用 `console.log` 输出业务日志。**

**【强制】禁止在日志中打印敏感信息（密码、身份证、银行卡号、Token、全量报文），需脱敏后输出。**

**反例：**
```javascript
// ❌ 错误：日志打印敏感信息
logger.info('用户登录成功', { username, password });
logger.info('支付回调报文', callbackPayload); // 可能含银行卡号
```

**正例：**
```javascript
// ✅ 正确：脱敏后输出
logger.info('用户登录成功', { username });
logger.info('支付回调', { orderId, cardNo: maskCardNo(cardNo) });
```


## 七、异步与错误处理规范

**【强制】禁止使用回调嵌套（Callback Hell），必须使用 `async/await`；所有 `await` 必须包裹在 `try/catch` 或由统一错误处理中间件捕获。**

**反例：**
```javascript
// ❌ 错误：回调嵌套 + 未捕获异常
getUser(id, (err, user) => {
  if (err) throw err;
  getOrders(user.id, (err2, orders) => {
    if (err2) throw err2;
    res.json(orders);
  });
});
```

**正例：**
```javascript
// ✅ 正确：async/await + 统一错误处理
router.get('/user/:id/orders', async (req, res, next) => {
  try {
    const user = await userService.getUserById(req.params.id);
    const orders = await orderService.getOrdersByUserId(user.id);
    res.json(Result.success(orders));
  } catch (err) {
    next(err); // 统一错误处理中间件记录日志并返回标准错误结构
  }
});
```

**【强制】错误信息必须包含可追踪的上下文（业务 ID、关键参数），禁止吞掉异常后不记录日志。**


## 八、不确定需求处理

**【强制】遇到不确定的需求或缺少必要信息时，必须以 TODO 注释标注并停止实现，禁止自行猜测、虚拟需求。TODO 注释格式：`// TODO[AI]: 待确认/待实现的描述`。**

**反例：**
```javascript
// ❌ 错误：AI 自行猜测退款比例
const refundAmount = order.amount * 0.8; // 需求未明确退款比例
```

**正例：**
```javascript
// TODO[AI]: 退款金额计算规则未明确，需确认全额还是按比例退款
// const refundAmount = ???
```


## 九、快速检查清单

- [ ] 项目是否按业务模块组织，未按技术类型平铺？
- [ ] 架构分层是否清晰，是否存在跨层调用？
- [ ] 是否只实现了需求要求的功能，未过度编程？（是否通过了"三/四 提交前自查"？）
- [ ] 是否未做过度抽象（每个抽象都有 ≥2 个真实实现）？
- [ ] 是否未使用需求/技术设计未要求的设计模式与自造分层？
- [ ] 是否未引入项目未声明的依赖？
- [ ] 每个函数是否有中文 JSDoc/注释？
- [ ] 核心逻辑是否有中文行注释说明业务意图？
- [ ] 敏感操作（写库/写缓存/发消息/外部调用）是否打印了日志？
- [ ] 是否使用统一日志框架而非 `console.log`？
- [ ] 日志是否避免了打印密码、银行卡号等敏感信息？
- [ ] 是否使用 `async/await`，错误是否被统一捕获并记录日志？
- [ ] 不确定需求是否标注了 `// TODO[AI]:`？
