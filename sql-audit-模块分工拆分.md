# SQL 审核平台 · 模块分工与执行拆分

> 日期：2026-11-22｜依据：`sql-audit-platform-design-v2.md`（v2.1）｜团队规模假设：2 人
> 用途：直接作为执行派单依据。每张任务卡给出**交付物 / 依赖 / 验收**，可独立派给人或 agent。
> 原则：先冻结契约，再并行；关键路径只有一条，不要在非关键路径上投人。

---

## 0. 分工总原则

**关键路径是执行内核（parser → rule → rollback → executor），不是 CRUD 和前端。**

回滚正确性和幂等协议是这个平台唯一不可替代的价值，也是唯一写错了会造成生产事故的部分。排期、人力、评审注意力都应该压在这条线上。工单 CRUD、审批流、前端页面都是常规工程，可以后置、可以糙、可以外包给 agent 批量产出。

三条硬规矩：

1. **W0 契约没冻结，不准动业务代码。** 17 张表的 DDL 和 `sqlaudit-common` 里的核心模型是所有模块的共同依赖，不先定好必然返工。
2. **执行内核（流 A）由一个人（或一条 agent 流）独占，不拆给两个人。** 前镜像、隔离级别、租约、幂等是一个整体，拆开做必然在边界上出错。
3. **不要并行开发同一张表的写入方。** 同一张表有两个写入方时，先把该表的状态机画出来定好唯一写入点，再分工。

---

## 1. 模块依赖图与分层

```
          ┌─────────────── W0 契约层（必须先完成，所有人等它）────────────────┐
          │  sqlaudit-common：枚举 / DTO / 异常        平台库 DDL（Flyway V1） │
          └────────────────────────────┬─────────────────────────────────────┘
                                       │
        ┌──────────────────────────────┼──────────────────────────────┐
        │ 流 A · 执行内核（关键路径）    │ 流 B · 平台面                 │ 流 C · 查询通道
        │                              │                              │
        │  sqlaudit-parser             │  sqlaudit-auth               │  sqlaudit-query
        │        ↓                     │        ↓                     │   （依赖 parser
        │  sqlaudit-rule               │  sqlaudit-workflow           │    的只读判定）
        │        ↓                     │        ↓                     │
        │  sqlaudit-rollback           │  sqlaudit-audit              │
        │        ↓                     │                              │
        │  sqlaudit-executor           │  sqlaudit-storage            │
        └──────────────┬───────────────┴──────────────┬───────────────┘
                       │                              │
                  ┌────┴──────────────────────────────┴────┐
                  │ W2 集成：sqlaudit-server / -worker / 前端 │
                  └──────────────────┬────────────────────┘
                                     │
                            W3 验收：故障注入 4 用例 + 指标口径
```

`sqlaudit-storage` 位置特殊：它是流 B 的产出，但流 A 也要用。处理办法见 §2 的契约冻结——先定好 DAO 接口，流 A 用内存实现自测，W2 集成时换真实现。

---

## 2. W0 · 契约冻结（串行，2~3 天，一个人做完）

**这是整个拆分的地基。没有它，并行开发的两条流会产出两套互不兼容的 DTO。**

### 任务卡 W0-1 · 平台库 DDL 全量

- **交付物**：`sqlaudit-storage/src/main/resources/db/migration/V1__init.sql`，含 v2.1 §9 全部 23 张表
  （`user` / `role` / `user_role` / `instance` / `credential` / `rule` / `rule_group` / `rule_group_binding` /
  `flow_rule` / `flow_node` / `ticket` / `ticket_unit` / `audit_result` / `rollback_sql` / `rollback_statement` /
  `approval` / `query_log` / `audit_log` / `chain_head` / `exec_lock` / `masking_rule` / `export_task` / `worker_heartbeat`）
- **要点**：加密列长度按「明文 + 28B + 1.33 倍」倒推（§5.9）；`executed_at` **不加唯一约束**；
  `exec_fingerprint` 建**普通**索引；`chain_head` 初始化一行 `id=1, last_seq=0, last_hash=64×'0'`
- **验收**：空库跑 Flyway 成功；`ticket_unit` 的不变式（§3.3）写成 CHECK 约束或启动期断言；
  每张表的注释写明「唯一写入方是哪个模块」
- **依赖**：无
- **不要做**：不要用 JPA 自动建表。审计表的权限模型（应用账号只有 INSERT/SELECT）必须靠 DDL + GRANT 显式表达

### 任务卡 W0-2 · 核心模型与枚举

- **交付物**：`sqlaudit-common` 下的
  - `ParsedStatement`（§4.1 的字段，含 `parseConfidence` / `isReadOnly` / `dangerousClauses`）
  - `RuleHit`（code / severity / message / extra）
  - `RollbackPlan` + `RollbackCoverage` 枚举（FULL/PARTIAL/NONE）+ `UnavailableReason` 枚举
  - `AuditEvent`（§7.3 的 canonical 字段序，**顺序即哈希顺序，不准改**）
  - 状态枚举：`TicketStatus` / `UnitStatus` / `RollbackStatus`，含合法迁移表
  - `Dialect` 枚举（MYSQL/PG/IOTDB）
- **验收**：`AuditEvent` 的 canonical JSON 序列化有**黄金用例单测**（固定输入 → 固定 SHA256），
  后人改字段序时测试必须红
- **依赖**：无（可与 W0-1 并行）

### 任务卡 W0-3 · DAO 接口（给流 A 解耦用）

- **交付物**：`sqlaudit-storage` 的接口层（不含实现）：`TicketUnitDao` / `RollbackDao` / `ExecLockDao` / `AuditDao`
- **验收**：流 A 能用内存 fake 实现跑通单测，不依赖真实数据库
- **依赖**：W0-1（表结构定了才知道 DAO 签名）

---

## 3. W0' · P0 技术 spike（与 W0 并行，2~3 周，关键路径起点）

spike 的结论会改变流 A 的设计，**必须在流 A 正式开工前出结论**。设计文档 §10 已列，这里给执行口径。

| spike | 要回答的问题 | 失败时的 Plan B | 时长 |
|---|---|---|---|
| **S1 · PG 解析器选型** | `libpg_query` 的 Java 绑定（JNI）能否在开发机 + JDOS 容器双平台稳定构建？ | 退 Druid PG 模式 + **语句形态白名单**（只放行能完整解析的形态，其余一律高危人工审），不要退成「解析失败就放行」 | 3~5 天 |
| **S2 · 回滚可行性四类 case** | 无主键表 / 函数 SET / 批量 INSERT / 大事务，四类各自的真实表现 | 任一类无法可靠处理 → 写进 §5.2 覆盖率矩阵的 ❌ 栏，不要硬做 | 1 周 |
| **S3 · 隔离级别与幻行** | PG RR 下并发 INSERT 时，affected_rows 对账是否真的成立？MySQL next-key lock 是否按预期阻塞？ | 这条没有 Plan B。不成立就说明回滚能力无法保证，整个 P0 目标要重定 | 3~4 天 |
| **S4 · IoTDB 白名单** | `iotdb-session` 能否覆盖 MVP 需要的语句类型？元数据快照怎么导？ | 缩小 MVP 范围到「只审核不执行」，IoTDB 变更仍走人工 | 3 天 |

**spike 的交付物不是代码，是结论文档 + 可复现的验证脚本。** 每个 spike 写一页：问题、怎么验的、结论、对设计文档哪一节的影响。

---

## 4. W1 · 三条并行流

### 流 A · 执行内核（关键路径，一人独占）

#### A-1 · sqlaudit-parser

- **交付物**：三方言解析 → `ParsedStatement`；只读判定（§4.2 的 AST 白名单）；语句拆分（一条语句 = 一个 unit）
- **关键实现点**：
  - 只读判定**必须走 AST**，不准用正则（原型里的 `lintSql` 是演示用的，不要抄进后端）
  - 解析失败 → `parseConfidence=0` + `R_PARSE_FAILED`，**绝不降级放行**
  - `EXPLAIN ANALYZE` 必须判为非只读（PG 与 MySQL 8.0.18+ 都会真实执行）
- **验收**：§4.2 危险子句表的每一行都有一条用例；只读判定的假阴性率为 0（宁可误判为写操作）
- **依赖**：W0-2、S1 结论

#### A-2 · sqlaudit-rule

- **交付物**：规则引擎 + 27 条内置规则（§4.3）+ 风险分级 + 审批路由（取最严链）
- **关键实现点**：
  - 路由**取所有命中规则中最严的一条**，不是命中第一条
  - `non_overridable=1` 的规则任何配置都不能降级
  - 影响面评估**异步**（§4.4），规则审核同步返回
  - `R_AFFECT_TOO_MANY`（ERROR 阻断）与 `R_LOW_PEAK_WINDOW`（WARN 建议）是两条规则，不要合并
- **验收**：规则命中 → 审批链的映射有表驱动测试；「配置写错能否把 CRITICAL 降级」有专门的反例测试
- **依赖**：A-1

#### A-3 · sqlaudit-rollback

- **交付物**：前镜像采集、反向 SQL 生成、覆盖率判定、有效期与依赖检测
- **关键实现点**（每一条都是 v2.1 的修正项，照 v2 写会错）：
  - 前镜像用 `rw_exec` 连接，与 DML **同事务**；审核期绝不提前采集
  - 生成列必须从反向 INSERT 的列清单里排除
  - 反向语句落 `rollback_statement` 子表，**不是**单行 VARBINARY
  - 超阈值转对象存储，只在平台库留 URI
  - MySQL 用 `LAST_INSERT_ID()`（仅单条自增），批量 INSERT 一律 `not_available`
  - PG 的 `UPDATE ... RETURNING` 返回新值，**对回滚无用**，前镜像照样要先查
- **验收**：§5.2 覆盖率矩阵逐格有用例；生成的反向 SQL 过同方言 Parser 语法自检；
  影子库回放后数据与变更前逐字节一致
- **依赖**：A-1、S2/S3 结论

#### A-4 · sqlaudit-executor

- **交付物**：§3.3 抢占协议、§3.5 执行前门禁、单事务执行、affected_rows 对账、幂等确认、reaper
- **关键实现点**：
  - 抢占条件含 `intent_written=0`，覆盖 `pending`/`leased`
  - reaper 独立于抢占，按 `intent_written` 分流；**只认心跳不认耗时**（长 DDL 不能被误回收）
  - 幂等确认用条件 UPDATE，不靠唯一约束
  - 连接池固定隔离级别 + 执行前断言
  - `exec_lock` 同实例串行
- **验收**：**§10.2 的四个故障注入用例全过**。这是 P1 的硬门槛，过不了不准上线
- **依赖**：A-3、W0-3

### 流 B · 平台面（另一人）

#### B-1 · sqlaudit-auth

- **交付物**：企微 OAuth + 本地应急账号、JWT + refresh 轮换撤销、RBAC、凭据 AES-GCM 加解密
- **关键点**：`X-Forwarded-For` 按可信网关链解析（否则审计 IP 全是网关地址）；
  特权操作每次实时校验 `user.status`；主密钥走 KMS/环境变量，不落配置文件
- **验收**：禁用账号后特权操作立即失效；凭据页面不回显完整密码；查看凭据写审计
- **依赖**：W0-2

#### B-2 · sqlaudit-audit

- **交付物**：append-only 写入、hash 链（`chain_head` 行锁单写入点）、链头外置 WORM、检索导出、链校验任务
- **关键点**：
  - `seq` 由 `chain_head` 行锁内分配，**不用 AUTO_INCREMENT 的 id 串链**
  - 哈希算**明文**（密钥轮换不破链）
  - 创世 `prev_hash = 64×'0'`
  - canonical JSON 字段序固定，有黄金用例
- **验收**：并发 100 线程写入后 `seq` 连续无空洞、链校验通过；篡改任一行后校验必须报错
- **依赖**：W0-1、W0-2

#### B-3 · sqlaudit-workflow

- **交付物**：工单状态机、审批流（全局 L1 + 实例级覆盖 L2 + 最严链 L3）、防自审、会签/或签
- **关键点**：`approval.approver_id != ticket.creator_id` 硬校验；审批 ≠ 执行；
  实例级覆盖**只能加严**；状态机的非法迁移必须抛异常而不是静默忽略
- **验收**：状态机全迁移路径测试；「新接入的库漏配审批流」必须落到 L1 兜底而不是无审批
- **依赖**：W0-2、A-2（审批链需要风险等级）

#### B-4 · sqlaudit-storage 实现

- **交付物**：W0-3 接口的 MyBatis 实现、平台库与生产库连接池**物理隔离**、对象存储客户端
- **验收**：两套连接池的配置完全独立，误用会在启动期报错
- **依赖**：W0-1、W0-3

### 流 C · 查询通道（可由 agent 批量产出，依赖 A-1 的只读判定接口）

#### C-1 · sqlaudit-query

- **交付物**：只读执行、**AST 注入 LIMIT**、超时、限流、列级掩码、`query_log`
- **关键点**：
  - LIMIT 用 AST 注入，**不准字符串拼接**（§6.2 列了三种会出错的 SQL）
  - 三层保护：AST 注入 + `setMaxRows` + 流式读取提前 `cancel()`
  - 结果集不落库；`query_log` 只存加密 SQL 全文 + 行数 + 耗时
  - 结果页必须展示「平台已注入 LIMIT 1000」与改写后的实际 SQL
- **验收**：`SELECT ... LIMIT 10` / UNION / 子查询重名列三种 case 注入后语义不变；
  大表无 LIMIT 查询不会把服务端扫完
- **依赖**：A-1 的 `isReadOnly` 接口（接口先行，实现可后到）

---

## 5. W2 · 集成

| 任务 | 交付物 | 依赖 | 验收 |
|---|---|---|---|
| **sqlaudit-server** | Web/API 聚合、登录鉴权、工单与流程入口、审计写入；**关闭任务轮询开关** | 流 B 全部 + A-2 | 全部 API 有契约测试 |
| **sqlaudit-worker** | 装配 executor + query，独立部署，可多实例；**开启轮询** | A-4、C-1 | 多实例同时跑不重复执行同一 unit |
| **前端** | 9 个视图，照 `sql-audit-ui-prototype.html` 实现 | server API | 原型里的交互分支都能走通 |

前端可以直接把原型当设计稿用，但**三处不要照抄**：
1. `lintSql` 是正则演示，后端必须走 AST；
2. 原型的角色切换是演示用的前端状态，真实权限必须后端校验（前端隐藏按钮不是权限）；
3. 原型的 demo 数据是硬编码的，接真 API 时注意空态与错误态（原型里空态做了，错误态没做）。

---

## 6. 执行波次与并行度

```
周 1-3   ┃ W0 契约冻结（2~3 天）───┐
         ┃ W0' spike S1~S4 ────────┴──→ 结论文档
周 4-11  ┃ 流 A ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  ← 关键路径，不要抢人
         ┃ 流 B ━━━━━━━━━━━━━━━━━━━━━━━━━
         ┃ 流 C         ━━━━━━━━━━━
周 12-15 ┃ W2 集成 ━━━━━━━━━━
周 16    ┃ W3 验收（故障注入 4 用例 + 指标口径）
```

- 流 A 的 A-3/A-4 是整个项目最难的部分，**不要给它压缩排期**。设计文档已指出 v1 排期偏乐观 2~3 倍。
- 流 B、流 C 的任务卡颗粒度适合派给 agent 批量产出，人工只审关键点那几条。
- 流 A 的 A-3/A-4 **不建议交给 agent 独立完成**，必须人工逐行审：这两个模块写错不会报错，只会在出事故要回滚时才发现回滚是错的。

---

## 7. 派单给 workbuddy 时的建议

每张任务卡派单时带上三样东西，agent 才能独立完成：

1. **设计文档对应章节号**（本文每张卡都标了），不要让 agent 自己找；
2. **「关键实现点」原文**——这些是 v2.1 的修正项，agent 如果照通用 SQL 审核平台的常识写，会写回 v2 的错误版本；
3. **验收标准**，要求 agent 自己先写测试再写实现。

需要人工把关的三个点（不要全交给 agent）：
- A-3/A-4 的正确性（上面说了原因）；
- B-2 的 canonical JSON 字段序（一旦上线就不能改，改了历史链全废）；
- W0-1 的 DDL（所有模块的共同依赖，改一次全员返工）。
