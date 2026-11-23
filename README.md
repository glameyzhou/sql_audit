# SQL 审核平台

> 内部 SQL 变更的**唯一安全通道**——先审核、可回滚、全审计。

## 1. 这是什么，解决什么问题

公司办公网与生产数据库网络隔离，开发/运维要改线上数据只能「找人开白名单 + 直接连库改」，带来的问题：

| 痛点 | 后果 |
|---|---|
| 没有审核 | 一条 `DELETE` 没写 `WHERE` 直接清库 |
| 没有回滚 | 出事只能靠 binlog 抢救 / 手工重建，慢且易错 |
| 没有审计 | 谁改了什么、改没改成功，事后对不上 |
| 无差别直连 | 生产账号权限过大，一个人就能删库 |

**本平台把「改生产」收口成一条强制流水线**：提交 SQL → 解析 → 规则审核（高危拦截）→ 生成回滚 SQL → 逐级审批 → 由 worker 在唯一通道执行 → 前镜像采集 → 全量审计落链。办公网唯一能触达生产库的出口就是本平台的 worker，查询、变更、审计天然 100% 闭环。

## 2. 核心能力（按优先级）

1. **SQL 审核 + 回滚 SQL**（MySQL / PostgreSQL / IoTDB）——提交预览即给可回滚清单；不可回滚的高危语句硬拒绝。**执行前还有四道门禁（§3.5）**：并发互斥 / 结构漂移（DDL 指纹比对）/ 回滚能力复核 / 审批时效，任一不过绝不执行。出事用回滚 SQL 处理。
2. **线上 SQL 查询**——办公网无法直连生产库，通过平台查询通道带 LIMIT 保护 + 动态脱敏 + 查询审计。查询页提供库/表两列浏览（点表即查 10 条）、SQL 语法高亮与自动提示编辑器。
3. **全量操作审计**——append-only + hash 链，每人每步可追溯、防无意篡改。

## 3. 架构总览

```
                        ┌─────────────────────────────────────────────┐
  开发/运维 ──提交SQL──▶ │  sqlaudit-server（REST，办公网可访问）        │
                        │  parser → rule → rollback → workflow(审批)    │
                        └───────────────┬─────────────────────────────┘
                                        │ 队列（租约 + 心跳 + 预写 intent）
                                        ▼
                        ┌─────────────────────────────────────────────┐
                        │  sqlaudit-worker（唯一出口，防火墙只放行它）   │
                        │  executor → 生产库执行 + 前镜像采集             │
                        └───────────────┬─────────────────────────────┘
                                        ▼
                     MySQL / PostgreSQL / IoTDB（生产库，三类账号物理隔离）
                        ┌─────────────────────────────────────────────┐
                        │  audit（append-only + hash 链，全量留痕）      │
                        └─────────────────────────────────────────────┘
```

> **部署形态**：worker 已并入 server 单进程——mysql / postgres / h2 方言 profile 下，执行循环随 server 自动启动（`WorkerLoopLifecycle`），生产无需单独部署 worker 进程；memory（默认）profile 不启动执行循环。

**12 个后端模块**（Maven 多模块，Spring Boot 3.3.5 / JDK 21）：

| 模块 | 职责 |
|---|---|
| `sqlaudit-common` | 公共模型、枚举（状态机 14 态、风险 4 级） |
| `sqlaudit-storage` | 存储层：DAO 接口 + 三方言 JDBC 实现 + 内存 fake |
| `sqlaudit-parser` | SQL 解析（Druid AST，MySQL/PG 方言 visitor） |
| `sqlaudit-rule` | 审核规则（27 条 + 最严链） |
| `sqlaudit-rollback` | 回滚 SQL 生成（前镜像 + 反向 SQL + DDL 逆向） |
| `sqlaudit-executor` | 执行引擎（协议编排 + 执行前门禁） |
| `sqlaudit-query` | 查询通道（AST 白名单 + LIMIT 三层保护） |
| `sqlaudit-auth` | 账号与权限（三类生产账号物理隔离） |
| `sqlaudit-workflow` | 审批流 + 状态机（防自审、最严链） |
| `sqlaudit-audit` | 审计链（append-only + hash 链，单写入点） |
| `sqlaudit-server` | Web 入口（REST API + 提交编排） |
| `sqlaudit-worker` | 执行 worker（唯一生产库出口；已并入 server 单进程，真库 profile 下随 server 启动） |

## 4. 目录结构

```
sql_audit/                            ← git 仓库根
├── README.md                         本文件
├── sql-audit-platform-design-v2.md   设计文档（12 章，权威）
├── sql-audit-ui-prototype.html       UI 原型
├── sql-audit-模块分工拆分.md          模块分工
└── sql-audit/                        代码
    ├── backend/                      Maven 多模块（12 模块 + 父 pom）
    │   ├── sqlaudit-storage/         DAO 接口 + impl/{memory, jdbc/{mysql,postgresql,h2}}
    │   └── sqlaudit-server/          可启动 Web 入口（REST + 各 profile 装配）
    └── frontend/                     Vue 3 + Vite + Element Plus
```

## 5. 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 本机 SDKMAN 默认已切 21，直接 `mvn` 即可 |
| Maven | 3.x | 阿里云中央仓库镜像 |
| Node | 20+ | 前端（npm 走 npmmirror） |
| Docker | — | 跑 MySQL / PostgreSQL / IoTDB 容器 |

## 6. 快速开始（在家，MySQL）

```bash
# 1) 起数据库（Docker MySQL）
docker run -d --name xiangxin-mysql -e MYSQL_ROOT_PASSWORD=password -p 3306:3306 mysql:8.0

# 2) 建库（一次性；表结构由 Flyway 启动时自动迁移，无需手动跑 DDL）
mysql -h127.0.0.1 -uroot -ppassword -e \
  "CREATE DATABASE IF NOT EXISTS \`sql-audit\` DEFAULT CHARSET utf8mb4;"

# 3) 起后端（组名 dev-home = dev + mysql）
cd sql-audit/backend
mvn install -DskipTests   # 多模块依赖（sqlaudit-worker 等）先进本地仓库，否则 spring-boot:run 拿旧 jar
SPRING_PROFILES_ACTIVE=dev-home mvn -pl sqlaudit-server spring-boot:run
# 端口 8888；Flyway 自动建 23 张 t_ 前缀表 + 创世块 + 4 角色种子

# 4) 起前端（另开终端）
cd sql-audit/frontend
npm install        # 首次
npm run dev        # 端口 5173，/api 代理到 8888
```

浏览器打开 http://localhost:5173 。

> **全局环境切换**：顶栏右上角「环境」下拉（开发 / 测试 / 生产），全局生效——提交变更的目标环境、实例过滤、查询页实例列表都跟随它；切到生产会弹二次确认。

### 6.1 默认账号（首次启动自动创建）

| 账号 | 密码 | 角色 | 用途 |
|---|---|---|---|
| `admin` | `admin12345` | ADMIN + LEAD + DBA + DEV | 超级管理员（全权限） |
| `dev` | `dev12345` | DEV | 开发（提交工单、查询通道） |

> 首次启动时由 `SeedUserRunner` 自动创建（无则建、有则跳过）。**生产环境务必改密码**，或接企微 OAuth 登录。

### 6.2 端到端测试的准备（实例 + 凭据，UI 一次配置）

提交 → 审批 → worker 自动执行的完整链路需要「生产实例」（本机测试 = 另一个 MySQL 库）与两类生产账号。

**实例与凭据都在「系统配置 → 实例管理」用 UI 维护**：新增实例时填写方言、环境、Host/端口/默认库和一组「账号 / 密码」，可先点「测试连接」验证连通性再保存。保存时凭据按 AES-GCM 加密写入 `t_credential`，**同一组账号同时落 RO_META（库表浏览 / 审批取结构指纹）与 RW_EXEC（worker 执行 / 回滚）两条**；dev / test / prod 所有环境一视同仁，**不再需要手工 SQL 种子**。编辑实例时账号名自动回填，密码留空表示不修改凭据。

只需准备目标库本身（示例）：

```sql
-- 目标「生产库」：建测试库和表
CREATE DATABASE IF NOT EXISTS `sql_audit_demo` DEFAULT CHARSET utf8mb4;
CREATE TABLE `sql_audit_demo`.t_demo (
  id INT PRIMARY KEY, a INT, b VARCHAR(32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO `sql_audit_demo`.t_demo VALUES (1, 10, 'x'), (2, 20, 'y');
```

之后在 UI 上：提交变更（如 `UPDATE sql_audit_demo.t_demo SET a = 1 WHERE id = 1`）→ 审批通过（此步采集结构指纹，RO_META 不通会 400）→ `dev-home` 下 worker 循环自动执行（观察「执行监控」页）→ 「回滚管理」页可见反向 SQL 并可执行回滚。

> 说明：MEDIUM 及以下工单审批通过即可执行；CRITICAL 工单还需提交人之外的确认人二次确认（§3.4，`confirm-execution`）。

## 7. 数据库初始化

- **建库**：一次性手动建（见上）。库名 `sql-audit`（含连字符）。
  - MySQL 建库要反引号：`` `sql-audit` ``
  - PostgreSQL 建库要双引号：`CREATE DATABASE "sql-audit"`（且 PG 的 Docker entrypoint 不支持连字符库名，需手动建）
- **建表**：**Flyway 启动时自动迁移**，迁移文件在 `sqlaudit-storage/src/main/resources/db/migration/{mysql,postgresql,h2}/V1__init.sql`（23 张 `t_` 前缀表 + 审计链创世块 + 4 条内置角色）。
- **种子数据**：`V2__seed_rule_flow.sql`（三方言各一份）——26 条内置审核规则 + L1 全局默认审批流 4 级（LOW 免审 / MEDIUM=LEAD / HIGH=LEAD→DBA / CRITICAL=LEAD→DBA→ADMIN 不可降级），与代码 `BuiltInRules` / `FlowConfig` 默认值一一对应。
- **改表结构**：新增 `V3__xxx.sql`，**绝不改 `V1__init.sql` / `V2__seed_rule_flow.sql`**（Flyway 迁移历史不可变）。

## 8. 环境与方言（核心）

**两个正交维度**，用命名组合（profile group）一个词搞定：`SPRING_PROFILES_ACTIVE=<组名>`。

### 8.1 四个命名组合（直接背这个就行）

| 组名 | 展开 | 场景 |
|---|---|---|
| `dev-home` | dev + mysql | **在家**：本机 Docker MySQL（127.0.0.1） |
| `dev-company` | dev + postgres | **公司开发**：PG，地址走 `SQLAUDIT_PG_URL` 注入 |
| `test` | test + postgres | **公司测试**：PG test 库，地址注入 |
| `prod` | prod + postgres | **公司生产**：PG prod 库，地址注入 + WARN 日志 |

> 也可不背组名，手写组合：`SPRING_PROFILES_ACTIVE=dev,mysql` / `dev,postgres` / `test,h2` …

### 8.2 方言（存储实现，四选一）

| 方言 | profile | 数据库 | 决定 |
|---|---|---|---|
| 内存 fake | `memory`（默认） | 无 | 纯前端联调，数据不落库 |
| MySQL | `mysql` | MySQL 8 | `MySql*` DAO + Flyway `db/migration/mysql` |
| PostgreSQL | `postgres` | PG 16 | `Postgres*` DAO + Flyway `db/migration/postgresql` |
| H2 | `h2` | H2 内存（MODE=PostgreSQL） | `H2*` DAO + Flyway `db/migration/h2` |

### 8.3 环境（部署环境，三选一）

| 环境 | profile | 差异 |
|---|---|---|
| 开发 | `dev` | DEBUG 日志 |
| 测试 | `test` | INFO 日志 |
| 生产 | `prod` | WARN 日志，凭据必须走环境变量 |

### 8.4 连接环境变量（不同环境地址不同，靠它区分）

| 方言 | 环境变量 | 默认值 |
|---|---|---|
| mysql | `SQLAUDIT_DB_URL` / `SQLAUDIT_DB_USER` / `SQLAUDIT_DB_PASSWORD` | `127.0.0.1:3306/sql-audit` / root / password |
| postgres | `SQLAUDIT_PG_URL` / `SQLAUDIT_PG_USER` / `SQLAUDIT_PG_PASSWORD` | `127.0.0.1:5432/sql-audit` / postgres / postgres |

**密钥类环境变量**（都有内置开发默认密钥，本地可直接跑；**生产必须注入**）：

| 环境变量 | 用途 | 说明 |
|---|---|---|
| `SQLAUDIT_JWT_SECRET` | 登录 JWT 签名（HS256） | 任意随机串；泄漏即可伪造任意身份 |
| `SQLAUDIT_CREDENTIAL_KEY` | 生产库凭据 AES-GCM 密钥 | base64 的 32 字节随机密钥（或任意串自动补齐） |
| `SQLAUDIT_DETAIL_KEY` | 审计明细 AES-GCM 密钥 | 同上；与凭据密钥隔离，轮换不破审计 hash 链 |

**公司各环境怎么配不同地址**：同一套代码 + 同一组名（如 `test`），只换环境变量指向不同库：

```bash
# 公司测试环境
SPRING_PROFILES_ACTIVE=test \
  SQLAUDIT_PG_URL='jdbc:postgresql://pg-test.internal:5432/sql-audit' \
  SQLAUDIT_PG_USER=sqlaudit SQLAUDIT_PG_PASSWORD=*** \
  mvn -pl sqlaudit-server spring-boot:run

# 公司生产环境
SPRING_PROFILES_ACTIVE=prod \
  SQLAUDIT_PG_URL='jdbc:postgresql://pg-prod.internal:5432/sql-audit' \
  SQLAUDIT_PG_USER=sqlaudit SQLAUDIT_PG_PASSWORD=*** \
  mvn -pl sqlaudit-server spring-boot:run
```

> 生产凭据严禁写进仓库，只走环境变量（或 CI/CD 的密钥系统注入）。

## 9. Docker 启动数据库

```bash
# MySQL（在家 dev）
docker run -d --name xiangxin-mysql \
  -e MYSQL_ROOT_PASSWORD=password -p 3306:3306 mysql:8.0

# PostgreSQL（公司 dev / 联调；连字符库名需手动建库）
docker run -d --name sqlaudit-pg \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16-alpine
docker exec sqlaudit-pg psql -U postgres -c 'CREATE DATABASE "sql-audit";'

# IoTDB（被审核的时序库实例，可选）
docker run -d --name iotdb -p 6667:6667 apache/iotdb:2.0.11-standalone
```

## 10. 停止服务

```bash
# 前端/后端：在各自终端 Ctrl+C
# 或按端口杀进程
lsof -ti:8888 | xargs kill    # 后端
lsof -ti:5173 | xargs kill    # 前端
docker stop xiangxin-mysql sqlaudit-pg   # 停数据库容器
```

## 11. 测试

```bash
cd sql-audit/backend
mvn clean test        # 全量 423 测试（含 MySQL/PG 真库 IT + H2 IT；需 Docker 在跑）
```

## 12. FAQ

**Q：默认不传 `SPRING_PROFILES_ACTIVE` 会怎样？**
A：默认 `memory` 方言（无库可跑，数据不落库），适合只看界面/纯前端联调。

**Q：启动报 `Access denied for user`？**
A：MySQL 容器没起或密码不对，`docker ps` 确认容器在跑。

**Q：启动报 `Could not find class [com.sqlaudit.worker.config.WorkerConfig]` 或 `NoClassDefFoundError`？**
A：本地仓库里的 `sqlaudit-*` 快照 jar 过期（`spring-boot:run` 依赖本地仓库而非 reactor）。在 `backend/` 下先 `mvn install -DskipTests` 再启动。

**Q：Flyway 报 `Unsupported Database`？**
A：方言与依赖不匹配。PG 方言需要 `flyway-database-postgresql` 依赖（已配）；确认 profile 拼写正确（`postgres` 不是 `postgresql`）。

**Q：想改表结构怎么办？**
A：新增 `V2__xxx.sql`，绝不改 `V1__init.sql`（改了 Flyway 校验会失败）。

**Q：为什么表名都是 `t_` 前缀、库名 `sql-audit`？**
A：`t_` 前缀规避 `user`/`role` 等 PG 保留字 + 统一规范；`sql-audit` 是定版命名。注意库名连字符在 PG 里需双引号建库。

**Q：公司各环境的 PG 地址不一样，怎么切？**
A：组名不变（`test`/`prod`），只改 `SQLAUDIT_PG_URL` 环境变量指向对应库。代码零改动。

---

*详见设计文档 `sql-audit-platform-design-v2.md`（§2 架构 / §3 执行协议 / §5 回滚 / §7 审计 / §8 权限）。*
