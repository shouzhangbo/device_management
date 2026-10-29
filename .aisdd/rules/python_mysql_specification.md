---
name: Python MySQL 数据库开发规范
version: 1.0.0
description: 面向 AI Agent 的 Python MySQL 开发规范。覆盖驱动选择（PyMySQL / mysqlclient / asyncmy / aiomysql）、连接池（DBUtils / SQLAlchemy pool）、SQLAlchemy 2.0 ORM 与 Core、参数化查询防 SQL 注入、连接生命周期管理（with / async with）、事务控制（commit / rollback / savepoint）、软删除模式、字段类型映射、批量插入、批量更新、N+1 查询优化、连接健康检查、慢查询日志、Schema 迁移（Alembic）、只读从库路由。Python 3.x 默认支持。
---

# Python MySQL 数据库开发规范

## 前言

MySQL 是最流行的开源关系型数据库，Python 生态提供多种驱动与 ORM 接入方式。本文档基于 SQLAlchemy 2.0、PyMySQL、asyncmy 等主流库的最佳实践，结合阿里巴巴 MySQL 规范（参见 `mysql_specification.md`）的工程经验，为 Python 项目访问 MySQL 提供正反例对照。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。

> Python 3.x 默认支持；本规范不涉及 Python 2.x 兼容代码。

## 一、驱动选择

### 1.1 同步驱动

**【推荐】同步项目使用 `PyMySQL`（纯 Python，跨平台、易安装）或 `mysqlclient`（C 实现，性能更优但需编译）。**

### 1.2 异步驱动

**【强制】FastAPI / asyncio 项目必须使用异步驱动：`asyncmy`（生产推荐）或 `aiomysql`。**

**正例：**
```python
# ✅ 正确：FastAPI 异步项目使用 asyncmy
DATABASE_URL = "mysql+asyncmy://user:pass@localhost:3306/mydb"
engine = create_async_engine(DATABASE_URL, pool_size=20, max_overflow=10)
```

**反例：**
```python
# ❌ 错误：异步项目使用同步驱动，阻塞事件循环
DATABASE_URL = "mysql+pymysql://user:pass@localhost:3306/mydb"  # 同步！
engine = create_engine(DATABASE_URL)
```

## 二、连接管理

### 2.1 必须使用连接池

**【强制】所有生产代码必须使用连接池（DBUtils / SQLAlchemy pool / asyncmy pool），禁止每次请求新建连接。**

**反例：**
```python
# ❌ 错误：每次请求新建连接，性能差且易耗尽数据库连接数
def get_user(user_id):
    conn = pymysql.connect(host="...", user="...", password="...", database="mydb")
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM user WHERE id = %s", (user_id,))
    user = cursor.fetchone()
    conn.close()  # 每次都新建/关闭
    return user
```

**正例（SQLAlchemy 同步）：**
```python
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, Session

engine = create_engine(
    DATABASE_URL,
    pool_size=20,
    max_overflow=10,
    pool_pre_ping=True,      # 健康检查
    pool_recycle=3600,       # 1 小时回收
    echo=False,
)
SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)

def get_user(user_id: int) -> User | None:
    with SessionLocal() as session:
        return session.get(User, user_id)
```

### 2.2 连接健康检查

**【推荐】启用 `pool_pre_ping=True`，每次获取连接时先执行 `SELECT 1`，自动剔除失效连接。**


## 三、参数化查询（防 SQL 注入）

### 3.1 强制参数化

**【强制】所有用户输入必须通过参数化占位符（`%s` / `:name`）传入，禁止任何形式的字符串拼接构造 SQL。**

**反例（致命）：**
```python
# ❌ 致命错误：字符串拼接，SQL 注入
username = request.args.get("username")
cursor.execute(f"SELECT * FROM user WHERE username = '{username}' AND password = '{password}'")
# 输入 ' OR '1'='1 即绕过认证
```

**正例：**
```python
# ✅ 正确：参数化
cursor.execute(
    "SELECT * FROM user WHERE username = %s AND password = %s",
    (username, hashed_password),
)

# SQLAlchemy ORM（自动参数化）
session.query(User).filter(User.username == username).first()

# SQLAlchemy text()（显式 bindparam）
from sqlalchemy import text
session.execute(
    text("SELECT * FROM user WHERE username = :name"),
    {"name": username},
)
```


## 四、SQLAlchemy ORM 规范

### 4.1 模型定义

**【强制】每个 ORM 模型必须显式声明 `__tablename__`、主键、必填字段；推荐添加字段注释（通过 `info={"comment": "..."}` 或 docstring）。**

**正例：**
```python
from sqlalchemy import Column, Integer, String, DateTime, Boolean, Index
from sqlalchemy.sql import func

class User(Base):
    """用户表。"""
    __tablename__ = "user"

    id = Column(Integer, primary_key=True, autoincrement=True, comment="用户ID")
    username = Column(String(50), nullable=False, unique=True, comment="用户名")
    email = Column(String(100), nullable=False, comment="邮箱")
    is_active = Column(Boolean, default=True, nullable=False, comment="是否激活")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")
    updated_at = Column(DateTime, onupdate=func.now(), comment="更新时间")

    __table_args__ = (
        Index("idx_user_email", "email"),
        Index("idx_user_created_at", "created_at"),
        {"comment": "用户主表"},
    )
```

### 4.2 2.0 新式 API

**【推荐】新代码使用 SQLAlchemy 2.0 风格的 `select()` / `session.execute()` API，替代老式 `Query` 对象。**

**反例：**
```python
# ❌ 老式 API（1.x）
user = session.query(User).filter(User.id == 1).first()
```

**正例：**
```python
# ✅ 正确：2.0 新式 API
from sqlalchemy import select

stmt = select(User).where(User.id == 1)
user = session.execute(stmt).scalar_one_or_none()
```

### 4.3 关系加载（防 N+1）

**【强制】禁止在循环中触发懒加载查询；预加载关联数据使用 `selectinload` / `joinedload`。**

**反例（致命性能问题）：**
```python
# ❌ N+1 查询：循环触发懒加载
users = session.execute(select(User)).scalars().all()
for user in users:
    print(user.orders)  # 每次访问都查一次数据库，N 个用户 = N+1 次查询
```

**正例：**
```python
# ✅ 正确：一次性预加载
from sqlalchemy.orm import selectinload

users = session.execute(
    select(User).options(selectinload(User.orders))
).scalars().all()
for user in users:
    print(user.orders)  # 已加载，不再查库
```


## 五、事务规范

### 5.1 显式事务边界

**【强制】涉及多表写入 / 多步更新必须包裹在显式事务中，任何异常必须回滚。**

**正例（同步）：**
```python
def transfer_balance(from_id: int, to_id: int, amount: int):
    """转账：扣减源账户 + 增加目标账户，原子操作。"""
    with SessionLocal() as session:
        try:
            with session.begin():  # 显式事务
                from_user = session.get(User, from_id, with_for_update=True)  # 行锁
                to_user = session.get(User, to_id, with_for_update=True)
                if from_user.balance < amount:
                    raise InsufficientBalanceError()
                from_user.balance -= amount
                to_user.balance += amount
            # 自动 commit
        except Exception:
            session.rollback()  # with 会自动关闭，rollback 双保险
            raise
```

**正例（异步）：**
```python
async def transfer_balance(from_id: int, to_id: int, amount: int):
    """异步转账。"""
    async with AsyncSessionLocal() as session:
        try:
            async with session.begin():
                from_user = await session.get(User, from_id, with_for_update=True)
                to_user = await session.get(User, to_id, with_for_update=True)
                if from_user.balance < amount:
                    raise InsufficientBalanceError()
                from_user.balance -= amount
                to_user.balance += amount
        except Exception:
            await session.rollback()
            raise
```

### 5.2 嵌套事务（SAVEPOINT）

**【推荐】子业务异常不应回滚整个事务，使用 `session.begin_nested()` 创建 SAVEPOINT。**


## 六、批量操作

### 6.1 批量插入

**【推荐】单次插入超过 100 行必须使用批量插入；推荐 500-1000 行/批。**

**正例：**
```python
# ✅ 正确：批量插入
from sqlalchemy import insert

users_to_insert = [{"username": f"user_{i}", "email": f"u{i}@x.com"} for i in range(1000)]

with SessionLocal() as session:
    with session.begin():
        # 方法 1：core 批量插入
        session.execute(insert(User), users_to_insert)

        # 方法 2：ORM 分批
        for i in range(0, len(users_to_insert), 500):
            batch = [User(**u) for u in users_to_insert[i:i+500]]
            session.add_all(batch)
            session.flush()
```

### 6.2 批量更新

**【推荐】批量更新使用 `UPDATE ... WHERE id IN (...)`，禁止 N 次单条更新。**

**反例：**
```python
# ❌ N 次更新
for user_id in user_ids:
    user = session.get(User, user_id)
    user.status = "active"
```

**正例：**
```python
# ✅ 单次批量更新
from sqlalchemy import update
session.execute(
    update(User).where(User.id.in_(user_ids)).values(status="active")
)
```


## 七、Schema 迁移

**【强制】所有表结构变更必须使用 Alembic 版本化迁移，禁止手动 ALTER TABLE。**

**正例：**
```bash
# 生成迁移脚本
alembic revision --autogenerate -m "add user_phone column"

# 应用迁移
alembic upgrade head

# 回滚
alembic downgrade -1
```

**【强制】生产环境部署前必须先备份再迁移；DML 回填必须分批执行避免长事务。**


## 八、连接关闭与资源释放

**【强制】所有 Session 必须通过 `with` 上下文自动关闭，禁止依赖 GC 回收连接。**

**反例：**
```python
# ❌ 错误：手动管理，异常路径会泄漏连接
session = SessionLocal()
user = session.query(User).first()
# ... 异常未处理 ...
session.close()
```

**正例：**
```python
# ✅ 正确：with 上下文保证关闭
with SessionLocal() as session:
    user = session.execute(select(User)).scalar_one_or_none()
    # ... 异常自动回滚 + 关闭 ...
```


## 九、敏感操作日志

**【强制】写库（INSERT / UPDATE / DELETE）操作必须打印 INFO 日志，包含操作类型、影响行数、关键参数；慢查询（>200ms）必须打印 WARN。**

**正例：**
```python
import logging
logger = logging.getLogger(__name__)

def update_user_status(user_id: int, status: str) -> None:
    """更新用户状态。"""
    with SessionLocal() as session:
        affected = session.execute(
            update(User).where(User.id == user_id).values(status=status)
        )
        session.commit()
        logger.info(
            "更新用户状态 user_id=%s status=%s affected=%d",
            user_id, status, affected.rowcount,
        )
```

## 十、慢查询定位

**【推荐】通过 SQLAlchemy 事件钩子捕获执行时间 >200ms 的慢查询，自动记录 WARN 日志：**

```python
import time
from sqlalchemy import event

@event.listens_for(engine, "before_cursor_execute")
def before_cursor_execute(conn, cursor, statement, parameters, context, executemany):
    context._query_start_time = time.time()

@event.listens_for(engine, "after_cursor_execute")
def after_cursor_execute(conn, cursor, statement, parameters, context, executemany):
    total = time.time() - context._query_start_time
    if total > 0.2:
        logger.warning("慢查询耗时=%.3fs SQL=%s", total, statement[:200])
```

## 十一、禁止事项

**【强制】禁止以下做法：**

1. ❌ 在循环中执行单条 SQL（必须批量）
2. ❌ 使用 `SELECT *`（必须显式字段）
3. ❌ 使用 `ORM` 中 `Query` 老式 API（新项目用 2.0）
4. ❌ 字符串拼接构造 SQL
5. ❌ 在 async 函数中使用同步驱动
6. ❌ 依赖 GC 回收数据库连接
7. ❌ 在事务内执行 HTTP / RPC 等外部调用（延长事务持有时间）


## 十二、快速检查清单

- [ ] 异步项目是否使用 `asyncmy` / `aiomysql`？
- [ ] 是否使用连接池（`pool_size` / `max_overflow`）？
- [ ] 是否启用 `pool_pre_ping` 健康检查？
- [ ] 所有 SQL 是否参数化（无字符串拼接）？
- [ ] 关联查询是否预加载（`selectinload` / `joinedload`）？
- [ ] 事务是否有显式 `begin` / `commit` / `rollback`？
- [ ] Session 是否通过 `with` 自动关闭？
- [ ] 表结构变更是否走 Alembic 迁移？
- [ ] 写操作是否记录 INFO 日志？
- [ ] 慢查询（>200ms）是否捕获并告警？