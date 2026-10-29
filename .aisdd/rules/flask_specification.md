---
name: Flask Web 框架开发规范
version: 1.0.0
description: 面向 AI Agent 的 Flask（>=2.3）开发规范。覆盖应用工厂模式、Blueprint 模块化、扩展管理、配置类分层、请求上下文（g / current_app）、中间件/钩子、Jinja2 模板、Flask-SQLAlchemy 集成、Flask-Login 认证、限流、缓存、CORS 与安全响应头。参考 Flask 官方文档、Pallets 生态最佳实践与 GitHub 上 star 过千的开源项目（flasky / cookiecutter-flask）。
---

# Flask Web 框架开发规范

## 前言

Flask 是 Python 生态中最经典的轻量级 Web 框架，凭借灵活的扩展机制和极低的学习曲线，被广泛应用于 API 服务、内部工具、管理后台与原型验证。

本文档基于 Flask 官方文档、Pallets 生态最佳实践以及 GitHub 上高 star 开源项目（如 cookiecutter-flask、flasky）的工程经验，为 AI Agent 生成 Flask 代码提供正反例对照。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。

> Python 3.x 默认支持；本规范不涉及 Python 2.x 兼容代码。

## 一、应用工厂模式

### 1.1 必须使用应用工厂

**【强制】所有 Flask 项目（无论大小）必须使用 `create_app(config_name)` 应用工厂模式，禁止在模块顶层直接 `app = Flask(__name__)`。**

**反例：**
```python
# ❌ 错误：模块级 app，无法多实例、无法灵活切换配置
from flask import Flask
from flask_sqlalchemy import SQLAlchemy

app = Flask(__name__)
app.config["SQLALCHEMY_DATABASE_URI"] = "postgresql://..."
db = SQLAlchemy(app)  # 立即绑定，无法解耦

@app.route("/")
def index():
    return "Hello"
```

**正例：**
```python
# ✅ 正确：应用工厂模式
# app/__init__.py
from flask import Flask
from app.extensions import db, migrate, login_manager
from config import config

def create_app(config_name: str = "development") -> Flask:
    """应用工厂：根据配置名创建 Flask 实例。"""
    app = Flask(__name__)
    app.config.from_object(config[config_name])

    # 延迟绑定扩展（避免循环导入）
    db.init_app(app)
    migrate.init_app(app, db)
    login_manager.init_app(app)

    # 注册蓝图
    from app.routes.auth import auth_bp
    from app.routes.main import main_bp
    app.register_blueprint(auth_bp, url_prefix="/auth")
    app.register_blueprint(main_bp)

    return app

# wsgi.py 或 run.py
from app import create_app
app = create_app(os.getenv("FLASK_ENV", "development"))
```

### 1.2 扩展延迟初始化

**【强制】所有 Flask 扩展必须在 `extensions.py` 中创建实例但不绑定 app，在工厂内通过 `init_app(app)` 绑定。**

**正例：**
```python
# app/extensions.py
from flask_sqlalchemy import SQLAlchemy
from flask_migrate import Migrate
from flask_login import LoginManager
from flask_caching import Cache
from flask_limiter import Limiter
from flask_cors import CORS

db = SQLAlchemy()
migrate = Migrate()
login_manager = LoginManager()
cache = Cache()
limiter = Limiter(key_func=get_remote_address)
cors = CORS()
```

## 二、Blueprint 模块化

### 2.1 按业务功能拆分蓝图

**【强制】项目 ≥5 个路由时必须按业务功能（users / orders / auth）拆分 Blueprint，禁止所有路由写在同一个文件中。**

**正例：**
```python
# app/users/__init__.py
from flask import Blueprint

users_bp = Blueprint("users", __name__, url_prefix="/users",
                     template_folder="templates")

from app.users import views  # 放在最后避免循环导入

# app/users/views.py
from app.users import users_bp
from app.users.services import UserService

@users_bp.route("/", methods=["GET"])
def list_users():
    """用户列表页。"""
    users = UserService.get_all()
    return render_template("users/list.html", users=users)
```

**【推荐】Blueprint 内部进一步分为 views / services / models / forms 四个文件，遵循 MVC 变体。**

## 三、配置管理

### 3.1 配置类分层

**【强制】必须为每个环境（development / testing / production）定义独立配置类，通过 `FLASK_ENV` 环境变量切换。**

**正例：**
```python
# config.py
import os
from dotenv import load_dotenv

load_dotenv()

class BaseConfig:
    """所有环境共享的基础配置。"""
    SECRET_KEY = os.environ.get("SECRET_KEY")
    if not SECRET_KEY:
        raise RuntimeError("SECRET_KEY environment variable is required")
    SQLALCHEMY_TRACK_MODIFICATIONS = False
    JSON_SORT_KEYS = False

class DevelopmentConfig(BaseConfig):
    DEBUG = True
    SQLALCHEMY_DATABASE_URI = os.environ.get("DEV_DATABASE_URL")
    SQLALCHEMY_ECHO = True

class TestingConfig(BaseConfig):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = "sqlite:///:memory:"
    WTF_CSRF_ENABLED = False

class ProductionConfig(BaseConfig):
    DEBUG = False
    SQLALCHEMY_DATABASE_URI = os.environ.get("DATABASE_URL")
    SESSION_COOKIE_SECURE = True
    SESSION_COOKIE_HTTPONLY = True
    SESSION_COOKIE_SAMESITE = "Lax"

config = {
    "development": DevelopmentConfig,
    "testing": TestingConfig,
    "production": ProductionConfig,
    "default": DevelopmentConfig,
}
```

### 3.2 敏感配置保护

**【强制】所有敏感配置（数据库密码、API Key、SECRET_KEY）必须从环境变量读取，`.env` 文件必须加入 `.gitignore`。**


## 四、请求与响应

### 4.1 统一 JSON 响应格式

**【强制】API 接口必须返回统一响应结构，禁止直接返回 dict / list。**

**正例：**
```python
# app/utils/response.py
from flask import jsonify

def success(data=None, message: str = "success", code: int = 200):
    """统一成功响应。"""
    return jsonify({"code": code, "message": message, "data": data}), code

def error(message: str, code: int = 400, data=None):
    """统一错误响应。"""
    return jsonify({"code": code, "message": message, "data": data}), code

# 使用
@users_bp.route("/api/users/<int:user_id>")
def api_get_user(user_id: int):
    user = UserService.get_by_id(user_id)
    if not user:
        return error("用户不存在", 404)
    return success(data=user.to_dict())
```

### 4.2 错误处理

**【强制】必须为常见 HTTP 状态码注册全局错误处理器，避免直接返回 HTML 错误页。**

**正例：**
```python
# app/__init__.py
from app.utils.response import error

@app.errorhandler(404)
def handle_404(e):
    return error("资源不存在", 404)

@app.errorhandler(500)
def handle_500(e):
    app.logger.exception("服务器内部错误")
    return error("服务器内部错误", 500)

@app.errorhandler(429)
def handle_429(e):
    return error("请求过于频繁，请稍后重试", 429)
```

## 五、数据库（Flask-SQLAlchemy）

### 5.1 模型定义

**【强制】模型必须显式声明 `__tablename__`，并使用 `db.Column(..., comment="...")` 添加字段注释；必须实现 `to_dict()` 序列化方法。**

**正例：**
```python
class User(db.Model):
    """用户模型。"""
    __tablename__ = "user"

    id = db.Column(db.Integer, primary_key=True, comment="用户ID")
    username = db.Column(db.String(50), unique=True, nullable=False, comment="用户名")
    email = db.Column(db.String(100), unique=True, nullable=False, comment="邮箱")
    is_active = db.Column(db.Boolean, default=True, comment="是否激活")
    created_at = db.Column(db.DateTime, default=datetime.utcnow, comment="创建时间")

    def to_dict(self) -> dict:
        """序列化为 dict。"""
        return {
            "id": self.id,
            "username": self.username,
            "email": self.email,
            "is_active": self.is_active,
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }
```

### 5.2 查询安全

**【强制】禁止字符串拼接构造 SQL；查询条件必须使用 ORM 参数化或 `text()` 占位符。**

**反例：**
```python
# ❌ 错误：SQL 注入风险
username = request.args.get("username")
User.query.filter(f"username = '{username}'").all()
```

**正例：**
```python
# ✅ 正确：使用 ORM 参数化
User.query.filter(User.username == username).first()

# 复杂条件
User.query.filter(
    User.username.ilike(f"%{username}%"),
    User.is_active == True,
).order_by(User.created_at.desc()).limit(20).all()
```


## 六、认证与权限（Flask-Login / JWT）

### 6.1 Flask-Login 集成

**【推荐】服务端渲染项目使用 Flask-Login；纯 API 项目使用 Flask-JWT-Extended。**

**正例：**
```python
# app/models.py
from flask_login import UserMixin

class User(UserMixin, db.Model):
    """用户模型（继承 UserMixin 集成 Flask-Login）。"""
    pass

# app/__init__.py
@login_manager.user_loader
def load_user(user_id):
    return User.query.get(int(user_id))

# 视图保护
from flask_login import login_required, current_user

@users_bp.route("/profile")
@login_required
def profile():
    return render_template("profile.html", user=current_user)
```

### 6.2 JWT 鉴权

**【强制】纯 API 项目必须使用 JWT 而非 Session 鉴权，Token 必须放在 `Authorization: Bearer <token>` Header 中。**

**正例：**
```python
from flask_jwt_extended import create_access_token, jwt_required, get_jwt_identity

@app.route("/api/login", methods=["POST"])
def login():
    user = UserService.authenticate(...)
    access_token = create_access_token(identity=user.id)
    return success(data={"access_token": access_token})

@app.route("/api/users/<int:user_id>")
@jwt_required()
def api_get_user(user_id):
    current_user_id = get_jwt_identity()
    if current_user_id != user_id:
        return error("无权访问", 403)
    ...
```


## 七、安全响应头

**【推荐】生产环境必须设置以下安全响应头，防止 XSS / Clickjacking / MIME Sniffing：**

**正例：**
```python
@app.after_request
def set_security_headers(response):
    """为所有响应添加安全头。"""
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    response.headers["Content-Security-Policy"] = "default-src 'self'"
    return response
```


## 八、Jinja2 模板

### 8.1 自动转义

**【推荐】默认依赖 Jinja2 的自动转义；禁止使用 `|safe` 标记用户输入内容，禁止关闭 `autoescape`。**

**反例：**
```html
<!-- ❌ 错误：用户输入未转义，XSS 风险 -->
<p>{{ user_input | safe }}</p>
```

**正例：**
```html
<!-- ✅ 正确：使用自动转义，必要时显式 safe 白名单 -->
<p>{{ user_input }}</p>
{% if content.html_content %}
    <div>{{ content.html_content | safe }}</div>
{% endif %}
```

### 8.2 模板组织

**【推荐】模板按业务模块分目录（`templates/users/`、`templates/orders/`），公共部分抽取为 `_base.html` / `_macros.html`。**


## 九、限流与防滥用

**【推荐】使用 `flask-limiter` 为敏感接口（登录、注册、支付）配置 IP / 用户级限流。**

**正例：**
```python
from flask_limiter.util import get_remote_address

@app.route("/api/login", methods=["POST"])
@limiter.limit("5 per minute", key_func=get_remote_address)
def login():
    ...
```


## 十、部署

### 10.1 生产 WSGI 服务器

**【强制】生产环境必须使用 Gunicorn / uWSGI，禁止使用 `flask run` 或 Werkzeug 内置服务器。**

**正例：**
```bash
gunicorn -w 4 -b 0.0.0.0:8000 "wsgi:app"
```

### 10.2 健康检查

**【推荐】注册 `/health` 接口供 Kubernetes / LB 健康探测：**

```python
@app.route("/health")
def health():
    return success(data={"status": "ok", "timestamp": datetime.utcnow().isoformat()})
```


## 十一、快速检查清单

- [ ] 是否使用 `create_app()` 应用工厂模式？
- [ ] 扩展是否在 `extensions.py` 延迟初始化？
- [ ] 配置是否分为 development / testing / production 三类？
- [ ] 路由是否按业务拆分为 Blueprint？
- [ ] API 响应是否统一格式（`{code, message, data}`）？
- [ ] SQL 查询是否全部参数化（无字符串拼接）？
- [ ] 敏感操作是否记录 INFO 级别日志？
- [ ] 生产是否设置安全响应头（X-Frame-Options / CSP）？
- [ ] 生产 WSGI 服务器是否为 Gunicorn/uWSGI？