---
name: FastAPI 异步 Web 框架开发规范
version: 1.0.0
description: 面向 AI Agent 的 FastAPI（>=0.110）开发规范。覆盖依赖注入系统（Depends / yield 依赖）、Pydantic v2 数据校验、APIRouter 模块化、async/await 异步处理、BackgroundTasks 后台任务、middleware（HTTP / CORS / GZip）、WebSocket 流式响应、异常处理（HTTPException / 自定义异常 + handler）、OpenAPI 文档生成、Settings 配置管理、SQLAlchemy 2.0 异步 ORM 集成、限流中间件、安全响应头、Production 部署（Uvicorn + Gunicorn + Docker）。参考 FastAPI 官方文档、tiangolo 维护的 full-stack-fastapi-template 与 GitHub 上 star 过千的开源项目。
---

# FastAPI 异步 Web 框架开发规范

## 前言

FastAPI 是基于 Starlette + Pydantic 构建的现代异步 Web 框架，凭借自动 OpenAPI 文档、原生 async/await 与强类型校验，成为 Python 后端 API 开发的首选。

本文档基于 FastAPI 官方文档、tiangolo/full-stack-fastapi-template 模板与 GitHub 上高 star 开源项目，提炼工业级最佳实践。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。

> Python 3.x 默认支持；本规范不涉及 Python 2.x 兼容代码。

## 一、项目结构

### 1.1 功能导向的模块化结构

**【强制】FastAPI 项目必须按业务功能垂直拆分（users / orders / auth），每个模块包含 router / service / repository / schemas / models。**

**正例：**
```
/app
├── main.py                  # 应用入口，注册路由 + 中间件
├── core/                    # 核心基础设施
│   ├── config.py           # Settings 配置
│   ├── database.py         # 异步 SQLAlchemy
│   ├── security.py         # JWT / 密码哈希
│   ├── dependencies.py     # 共享依赖（get_current_user 等）
│   ├── exceptions.py       # 自定义业务异常
│   └── response.py         # 统一响应结构
├── users/                   # 用户业务模块
│   ├── __init__.py
│   ├── router.py
│   ├── service.py
│   ├── repository.py
│   ├── schemas.py
│   └── models.py
├── orders/                  # 订单业务模块
│   └── ...
├── api/
│   ├── __init__.py
│   └── v1/
│       └── router.py       # v1 路由汇总
└── tests/
    ├── conftest.py
    └── test_users/
```


## 二、依赖注入

### 2.1 数据库会话依赖

**【强制】数据库会话必须通过 `Depends(get_db)` 注入，禁止在路由内手动 `SessionLocal()`。**

**反例：**
```python
# ❌ 错误：路由内手动管理会话，易泄漏
@router.get("/items")
async def get_items():
    db = SessionLocal()
    items = db.query(Item).all()
    db.close()
    return items
```

**正例：**
```python
# ✅ 正确：通过 yield 依赖管理生命周期
# app/core/database.py
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker
from app.core.config import settings

engine = create_async_engine(
    settings.DATABASE_URL,
    pool_pre_ping=True,
    pool_size=20,
    max_overflow=10,
)
AsyncSessionLocal = async_sessionmaker(engine, expire_on_commit=False)

async def get_db() -> AsyncSession:
    """获取数据库会话，请求结束自动关闭。"""
    async with AsyncSessionLocal() as session:
        try:
            yield session
        except Exception:
            await session.rollback()
            raise
```

### 2.2 共享依赖封装

**【推荐】将高频依赖（当前用户、当前租户、限流标识）封装在 `core/dependencies.py`，供全局复用。**

**正例：**
```python
# app/core/dependencies.py
from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from sqlalchemy.ext.asyncio import AsyncSession
from app.core.database import get_db
from app.core.security import decode_token
from app.users.repository import UserRepository

oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/v1/auth/login")

async def get_current_user(
    token: str = Depends(oauth2_scheme),
    db: AsyncSession = Depends(get_db),
) -> "User":
    """从 JWT Token 中解析当前登录用户。"""
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="无法验证身份",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = decode_token(token)
        user_id = payload.get("sub")
        if not user_id:
            raise credentials_exception
    except JWTError:
        raise credentials_exception

    user = await UserRepository(db).get_by_id(int(user_id))
    if not user or not user.is_active:
        raise credentials_exception
    return user
```


## 三、Pydantic 数据校验

### 3.1 必须使用 Pydantic 模型

**【强制】所有 API 请求/响应必须使用 Pydantic BaseModel 定义 Schema，禁止接收/返回裸 dict。**

**反例：**
```python
# ❌ 错误：参数裸 dict，无校验
@router.post("/users")
async def create_user(data: dict):
    email = data.get("email")  # 类型未知
    ...
```

**正例：**
```python
# ✅ 正确：Pydantic 模型强校验
from pydantic import BaseModel, EmailStr, Field, ConfigDict

class UserCreate(BaseModel):
    """用户创建请求。"""
    username: str = Field(..., min_length=3, max_length=50)
    email: EmailStr
    password: str = Field(..., min_length=8)
    age: int | None = Field(default=None, ge=0, le=150)

class UserResponse(BaseModel):
    """用户响应。"""
    id: int
    username: str
    email: EmailStr
    is_active: bool
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)  # 支持 ORM
```

### 3.2 字段约束

**【强制】Schema 字段必须显式声明 `min_length` / `max_length` / `ge` / `le` / `regex`，让校验在 Pydantic 层完成而非业务代码。**


## 四、异步数据库（SQLAlchemy 2.0）

### 4.1 必须使用异步驱动

**【强制】async def 路由必须使用异步数据库驱动（asyncpg / aiomysql）+ SQLAlchemy 2.0 async，禁止在 async 端点中调用同步阻塞 IO。**

**反例：**
```python
# ❌ 错误：async 路由内调用同步 SQLAlchemy，阻塞事件循环
@router.get("/items")
async def get_items():
    db = SessionLocal()
    items = db.query(Item).all()  # 阻塞！
    return items
```

**正例：**
```python
# ✅ 正确：使用 SQLAlchemy 2.0 异步
from sqlalchemy import select

@router.get("/items", response_model=list[ItemResponse])
async def get_items(db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Item))
    return result.scalars().all()
```

### 4.2 事务控制

**【强制】涉及多表写入必须用 `async with db.begin()` 或显式 commit / rollback，保证原子性。**

**正例：**
```python
async def transfer_balance(from_id: int, to_id: int, amount: int, db: AsyncSession):
    """转账：扣减源账户、增加目标账户。"""
    async with db.begin():
        from_user = await db.get(User, from_id, with_for_update=True)  # 行锁
        to_user = await db.get(User, to_id, with_for_update=True)
        if from_user.balance < amount:
            raise BusinessException(400, "余额不足")
        from_user.balance -= amount
        to_user.balance += amount
        # 自动 commit
```

## 五、异常处理

### 5.1 全局异常处理器

**【强制】必须实现全局异常处理器，统一 API 错误响应格式。**

**正例：**
```python
# app/core/exceptions.py
class BusinessException(Exception):
    """业务异常基类。"""
    def __init__(self, code: int, message: str):
        self.code = code
        self.message = message

class ResourceNotFoundError(BusinessException):
    def __init__(self, resource: str):
        super().__init__(404, f"{resource} 不存在")

# app/main.py
from fastapi import Request
from fastapi.responses import JSONResponse

@app.exception_handler(BusinessException)
async def business_exception_handler(request: Request, exc: BusinessException):
    return JSONResponse(
        status_code=exc.code if exc.code < 500 else 500,
        content={"code": exc.code, "message": exc.message, "data": None},
    )

@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.exception("未捕获异常")
    return JSONResponse(
        status_code=500,
        content={"code": 500, "message": "服务器内部错误", "data": None},
    )
```

### 5.2 业务异常继承

**【推荐】业务异常继承 `BusinessException` 基类，按 HTTP 状态码分类（400 / 401 / 403 / 404 / 409 / 422）。**


## 六、统一响应格式

**【强制】所有响应必须包装为统一结构 `{code, message, data}`。**

**正例：**
```python
# app/core/response.py
from typing import Generic, TypeVar
from pydantic import BaseModel

T = TypeVar("T")

class Result(BaseModel, Generic[T]):
    code: int = 200
    message: str = "success"
    data: T | None = None

    @classmethod
    def success(cls, data: T = None, message: str = "success") -> "Result[T]":
        return cls(code=200, message=message, data=data)

    @classmethod
    def error(cls, code: int, message: str) -> "Result[None]":
        return cls(code=code, message=message, data=None)
```


## 七、中间件

### 7.1 必备中间件

**【强制】生产环境必须配置：
1. CORS（明确允许来源）
2. GZip 压缩
3. 请求日志（记录 method / path / status / latency）
4. 全局异常捕获**

**正例：**
```python
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
import time

@app.middleware("http")
async def access_log_middleware(request: Request, call_next):
    """记录每个请求的访问日志。"""
    start = time.time()
    response = await call_next(request)
    latency_ms = (time.time() - start) * 1000
    logger.info(
        "HTTP %s %s -> %d (%.1fms)",
        request.method, request.url.path, response.status_code, latency_ms,
    )
    return response

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.ALLOWED_ORIGINS.split(","),
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "DELETE"],
    allow_headers=["Authorization", "Content-Type"],
)
app.add_middleware(GZipMiddleware, minimum_size=1000)
```

### 7.2 限流中间件

**【推荐】使用 `slowapi` 或自定义 Redis 滑动窗口限流中间件，保护敏感接口。**


## 八、配置管理

**【强制】使用 `pydantic-settings` 的 `BaseSettings` 管理配置，所有配置项必须从环境变量读取。**

**正例：**
```python
# app/core/config.py
from pydantic_settings import BaseSettings
from pydantic import Field

class Settings(BaseSettings):
    APP_NAME: str = "My FastAPI App"
    DEBUG: bool = Field(default=False, env="DEBUG")
    API_V1_STR: str = "/api/v1"

    DATABASE_URL: str = Field(..., env="DATABASE_URL")
    DATABASE_POOL_SIZE: int = Field(default=20, env="DB_POOL_SIZE")

    SECRET_KEY: str = Field(..., env="SECRET_KEY")
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30

    ALLOWED_ORIGINS: str = Field(default="http://localhost:3000", env="ALLOWED_ORIGINS")

    REDIS_URL: str = Field(default="redis://localhost:6379/0", env="REDIS_URL")
    KAFKA_BOOTSTRAP_SERVERS: str = Field(default="localhost:9092", env="KAFKA_BOOTSTRAP_SERVERS")

    class Config:
        env_file = ".env"
        case_sensitive = True
        extra = "ignore"

settings = Settings()
```


## 九、OpenAPI 文档

**【推荐】为每个接口添加 `summary` / `description` / `tags` / `response_model`，让自动生成的 OpenAPI 文档完整可读。**

**正例：**
```python
@router.post(
    "/",
    response_model=Result[UserResponse],
    status_code=status.HTTP_201_CREATED,
    summary="创建用户",
    description="根据请求体创建新用户，邮箱必须全局唯一",
    tags=["users"],
)
async def create_user(
    user_data: UserCreate,
    user_service: UserService = Depends(get_user_service),
) -> Result[UserResponse]:
    """创建新用户并返回用户信息。"""
    user = await user_service.create_user(user_data)
    return Result.success(data=user, message="用户创建成功")
```


## 十、后台任务与流式响应

### 10.1 BackgroundTasks

**【推荐】短任务（<10s）使用 `BackgroundTasks`；长任务使用 Celery / Dramatiq 等分布式任务队列。**

**正例：**
```python
from fastapi import BackgroundTasks

def send_welcome_email(user_email: str):
    """后台发送欢迎邮件。"""
    smtp_client.send(to=user_email, subject="欢迎注册", body="...")

@router.post("/register")
async def register(data: UserCreate, background_tasks: BackgroundTasks):
    user = await user_service.create(data)
    background_tasks.add_task(send_welcome_email, user.email)
    return Result.success(data=user)
```

### 10.2 流式响应（SSE / StreamingResponse）

**【推荐】LLM 输出、日志流、文件下载等场景使用 `StreamingResponse` 返回生成器，避免一次性加载大对象。**

**正例：**
```python
from fastapi.responses import StreamingResponse

@router.get("/stream")
async def stream_llm(prompt: str):
    """LLM 流式输出。"""
    async def generate():
        async for chunk in llm.astream(prompt):
            yield f"data: {chunk.content}\n\n"
        yield "data: [DONE]\n\n"
    return StreamingResponse(generate(), media_type="text/event-stream")
```


## 十一、安全

### 11.1 密码哈希

**【强制】密码必须使用 bcrypt / argon2 / passlib 哈希，禁止明文或 MD5/SHA1。**

```python
from passlib.context import CryptContext

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

def hash_password(plain: str) -> str:
    return pwd_context.hash(plain)

def verify_password(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain, hashed)
```

### 11.2 JWT

**【强制】使用 `python-jose` / `pyjwt` 签发与校验 JWT，设置合理的过期时间（Access Token ≤ 1h，Refresh Token ≤ 30d）。**


## 十二、生产部署

**【强制】生产环境使用 Uvicorn + Gunicorn 组合，禁止 `uvicorn main:app --reload`。**

**正例：**
```bash
gunicorn main:app -w 4 -k uvicorn.workers.UvicornWorker -b 0.0.0.0:8000
```

**Dockerfile 推荐：**
```dockerfile
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8000
CMD ["gunicorn", "main:app", "-w", "4", "-k", "uvicorn.workers.UvicornWorker", "-b", "0.0.0.0:8000"]
```


## 十三、快速检查清单

- [ ] 数据库会话是否通过 `Depends(get_db)` 注入？
- [ ] async 路由是否使用异步数据库驱动？
- [ ] 请求/响应是否使用 Pydantic 模型（无裸 dict）？
- [ ] 是否实现全局异常处理器统一错误响应？
- [ ] 响应是否统一 `{code, message, data}` 结构？
- [ ] CORS 是否显式配置 allow_origins（无 `*`）？
- [ ] 密码是否使用 bcrypt/argon2 哈希？
- [ ] 生产是否使用 Gunicorn + Uvicorn worker？
- [ ] 敏感配置是否从环境变量读取？