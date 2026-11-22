# SQL 审核平台 · 启动说明

> 内部工具，见名知意。三大优先级：① SQL 审核（MySQL/PG/IoTDB）+ 回滚 SQL ② 线上查询 ③ 全量审计。

## 1. 目录结构

```
sql_audit/                       ← 项目根（本文档 + 设计文档 + .workbuddy）
├── sql-audit-platform-design-v2.md   设计文档（12 章，权威）
└── sql-audit/                   ← 代码根（git 仓库）
    ├── backend/                 ← 后端 12 模块（Maven 多模块，Spring Boot 3.3.5）
    │   └── sqlaudit-server/     ← 可启动的 Web 入口
    └── frontend/                ← 前端（Vue 3 + Vite + Element Plus）
```

## 2. 前置依赖（已在本机就绪）

| 依赖 | 版本 | 备注 |
|---|---|---|
| JDK | 21（Temurin） | SDKMAN 安装，**需显式 `JAVA_HOME`**（SDKMAN 默认是 JDK8） |
| Maven | 3.x | 阿里云中央仓库镜像已配 |
| Node | 24 | npm 走 npmmirror 镜像 |
| MySQL | 8.0（Docker） | 容器 `xiangxin-mysql`，见下 |

## 3. 数据初始化（MySQL）

平台库为 **MySQL 8.0**，本机 Docker 已起容器 `xiangxin-mysql`（`root` / `password`，端口 3306，**勿动其中的 `ry-vue` 库**）。

### 3.1 建库（一次性）

```bash
mysql -h127.0.0.1 -uroot -ppassword -e \
  "CREATE DATABASE IF NOT EXISTS sqlaudit DEFAULT CHARSET utf8mb4;"
```

### 3.2 建表（自动，无需手跑）

**表结构由 Flyway 在应用启动时自动迁移**，迁移文件 `V1__init.sql`（23 张业务表 + 审计链创世块 + 4 条内置角色 `DEV/LEAD/DBA/ADMIN` 种子数据）位于 `backend/sqlaudit-storage/src/main/resources/db/migration/`。

- 首次以 `mysql` profile 启动 → 自动建表 + 写入种子数据；
- 之后每次启动 → Flyway 校验历史（`flyway_schema_history`），无新版本则跳过；
- **改动表结构 = 新增 `V2__xxx.sql`**，绝不改 `V1__init.sql`（迁移历史不可变）。

### 3.3 建表 collation 说明：`utf8mb4_0900_ai_ci`

`V1__init.sql` 建表语句带 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`，这是 **MySQL 8.0 的默认排序规则**，各段拆解：

| 段 | 含义 |
|---|---|
| `utf8mb4` | 字符集：完整 Unicode 的 UTF-8，每字符最多 4 字节（能存 emoji） |
| `0900` | **Unicode 版本号**：排序规则对齐 Unicode 9.0.0（UCA 9.0），不是 MySQL 自己的版本号 |
| `ai` | accent-insensitive：不区分重音（é = e，ñ = n） |
| `ci` | case-insensitive：不区分大小写（A = a） |

实际影响：字符串比较为**大小写不敏感 + 重音不敏感 + 按 Unicode 9.0 权重排序**。例：`WHERE account = 'Admin'` 能命中 `admin`，`'café'` 与 `'cafe'` 比较时视为相等。

> ⚠️ 跨版本迁移坑：`0900` 系列是 8.0 专有，**MySQL 5.7 不认识**，导入会直接报 `Unknown collation: utf8mb4_0900_ai_ci`。迁到 5.7 需把 collation 改成 `utf8mb4_general_ci` / `utf8mb4_unicode_ci`。本平台目标库固定 8.0，无此问题。

## 4. 启动后端

```bash
cd sql-audit/backend
export JAVA_HOME=/Users/yang.zhou/.sdkman/candidates/java/21.0.13.0+9-tem

# 首次（或改了依赖后）：把 12 个模块装进本地仓库
mvn install -DskipTests

# 启动（二选一）
mvn -pl sqlaudit-server spring-boot:run                     # ① memory 模式：无库可跑，用内存 fake DAO
SPRING_PROFILES_ACTIVE=mysql mvn -pl sqlaudit-server spring-boot:run   # ② mysql 模式：真库 + Flyway 迁移
```

- 端口 **8080**；
- **memory**（默认）：不连库，适合纯前端联调/快速起服务；数据不落盘；
- **mysql**：连本机 Docker MySQL，全链路落真库（生产/联调用）。

### 连接配置（环境变量覆盖，默认值在 `application.yml`）

| 变量 | 默认值 |
|---|---|
| `SQLAUDIT_DB_URL` | `jdbc:mysql://127.0.0.1:3306/sqlaudit?...&serverTimezone=Asia/Shanghai` |
| `SQLAUDIT_DB_USER` | `root` |
| `SQLAUDIT_DB_PASSWORD` | `password` |

日志（Log4j2）：控制台 + `logs/sqlaudit-app.log`（滚动）+ `logs/sqlaudit-error.log`（ERROR 单独，供告警）。目录随启动工作目录，生产用 `-DLOG_DIR=/var/log/sqlaudit` 固定。

## 5. 启动前端

```bash
cd sql-audit/frontend
npm install        # 首次
npm run dev        # 端口 5173，/api 已代理到 8080
```

浏览器开 http://localhost:5173 —— 登录页/9 视图骨架已就绪，侧边栏有品牌 logo（`src/assets/logo.svg`），设计规范见 `frontend/STYLE.md`。

## 6. 验证

```bash
# 提交预览（回滚清单 + 风险分级，无副作用）
curl -X POST http://localhost:8080/api/tickets/preview \
  -H "Content-Type: application/json" \
  -d '{"sql":"UPDATE t SET a=1 WHERE id=1","dialect":"MYSQL"}'

# 审计链完整性校验（mysql 模式下才真有数据）
curl "http://localhost:8080/api/audit-logs/verify?fromSeq=1"
```

跑测试：`cd sql-audit/backend && mvn clean test`（当前 278 条全绿；含 37 条真 MySQL 集成测试，需 Docker MySQL 在跑）。

## 7. 常见问题

| 现象 | 处理 |
|---|---|
| 启动报 `Access denied` | 确认 Docker 容器 `xiangxin-mysql` 在跑（`docker ps`） |
| mysql 模式报表已存在 | Flyway 历史与手建表冲突，`DROP DATABASE sqlaudit` 后重建，让 Flyway 重新迁移 |
| 端口 8080 被占 | `lsof -ti:8080 | xargs kill` |
| 想改表结构 | 新增 `V2__xxx.sql`，勿改 `V1__init.sql` |

---

*详见设计文档 `sql-audit-platform-design-v2.md`（§1 定位 / §2 架构 / §3 执行协议 / §5 回滚 / §7 审计 / §8 权限）。*
