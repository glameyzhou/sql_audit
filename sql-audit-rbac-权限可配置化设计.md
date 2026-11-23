# 角色-权限可配置化设计（RBAC 从硬编码迁移到 DB 可配置）

> 状态：已落地（2026-11-23）。目标：让「角色 → 权限点」可在系统界面配置，代码里的 `@RequireRole` 降级为**兜底默认值**，界面一旦配置即覆盖代码。

---

## 1. 背景与结论

**现状**：权限矩阵只在设计文档 `sql-audit-platform-design-v2.md` §8.2 里，真正生效的是代码里每个端点的 `@RequireRole` 注解，由 `RoleAuthInterceptor` 校验。角色只有 4 个（DEV/LEAD/DBA/ADMIN），改权限 = 改代码 + 重新发版。

**建议：值得做，但只做一层可配置，且要守住 3 条边界。**

1. 只把「**角色 ↔ 权限点**」做成可配置（DB 一张关联表 + 一个矩阵页）；
2. 「**权限点 ↔ 端点**」这层**不**做可配置——它属于代码语义，固化成枚举；
3. 「**谁能改权限**」（元权限）**永不**进 DB，硬编码 ADMIN，防止把自己锁死。

下面给出推荐方案的完整设计。

---

## 2. 总览架构图

```mermaid
flowchart LR
    subgraph 前端
        M[左侧菜单·权限配置页<br/>仅 ADMIN 可见] --> PC[PermissionController<br/>POST /api/permissions]
    end

    subgraph 后端
        PC --> PermDao[(t_role_permission<br/>t_permission)]
        PC --> Cache[(权限缓存)]
        PC -- 写审计 --> Audit[audit_log]

        Req[HTTP 请求 /api/**] --> INT[RoleAuthInterceptor]
        INT --> ANNO{端点有<br/>@RequirePermission?}
        ANNO -- 否 --> LEGACY[沿用 @RequireRole 旧路径]
        ANNO -- 是 --> CACHE[读权限缓存]
        CACHE --> CFG{该权限点<br/>customized?}
        CFG -- 是（界面配过） --> DBROLE[取 t_role_permission 角色集]
        CFG -- 否（未配过） --> FALLBACK[取 PermissionCode.defaultRoles()<br/>代码兜底]
        DBROLE --> CHECK{JWT 角色 ∩ 允许角色}
        FALLBACK --> CHECK
        CHECK -- 有交集 --> PASS[放行]
        CHECK -- 无交集 --> DENY[403]
    end
```

**一句话**：端点声明「需要哪个权限点」，权限点声明「默认哪些角色可用」（代码兜底），界面可把「哪些角色可用」改掉（DB 覆盖）。

---

## 3. 核心模型

### 3.1 权限点（PermissionCode 枚举，代码语义）

新增 `sqlaudit-common/.../enums/PermissionCode.java`，与 `RoleCode` 同风格。每个权限点带 `defaultRoles()` —— 即**现状 `@RequireRole` 写死的默认角色**，是兜底。

权限点目录（对齐 §8.2 矩阵 + 现状代码）：

| code | 名称 | 兜底默认角色 | 覆盖端点（现状） | 现状是否已强制 |
|---|---|---|---|---|
| `TICKET_SUBMIT` | 提交工单 | DEV/LEAD/DBA/ADMIN | TicketController | ❌ 业务逻辑 |
| `QUERY` | 查询通道 | DEV/LEAD/DBA/ADMIN | QueryController / InstanceController | ❌ 部分 |
| `APPROVE` | 审批（非本人） | LEAD/DBA/ADMIN | ApprovalController | ❌ 业务逻辑 |
| `EXECUTE` | 执行 / 二次确认 | DBA/ADMIN | RollbackController·execute 等 | ⚠️ 仅回滚执行 |
| `CONFIG_RULE_FLOW` | 规则 / 流程配置 | DBA/ADMIN | ConfigController·rules/flows | ✅ @RequireRole |
| `INSTANCE_ADMIN` | 实例接入与凭据 | ADMIN | ConfigController·instances/credentials | ✅ @RequireRole |
| `AUDIT_EXPORT` | 审计检索 / 导出 | DBA/ADMIN | AuditController | ❌ 未强制 |
| `USER_ADMIN` | 用户管理 | ADMIN | UserController | ✅ @RequireRole |
| `PERMISSION_ADMIN` | 权限配置（**元权限**） | ADMIN（硬编码，不可配置） | 新增 PermissionController | — |

> `PERMISSION_ADMIN` 是唯一**不进入可配置矩阵**的权限点：它始终要求 ADMIN，代码硬编码，界面不可改。这是防自锁的第一道闸。

### 3.2 注解

新增 `@RequirePermission(PermissionCode)`，标注在端点/控制器上。迁移后 4 个已强制资源用它替换 `@RequireRole`。

```java
// 迁移前
@RequireRole(RoleCode.ADMIN)
public class UserController { ... }

// 迁移后
@RequirePermission(PermissionCode.USER_ADMIN)   // 兜底默认 = ADMIN，见枚举 defaultRoles()
public class UserController { ... }
```

`@RequireRole` 保留给「非权限点」场景（如 `AuthController#/me` 的空注解 = 任意登录、`InstanceController` 的全角色 = 任意登录），语义不变。

### 3.3 数据模型

```sql
-- 权限点目录（由 PermissionCode 枚举启动时 insert-if-absent 同步，同 t_role 的 seed 方式）
CREATE TABLE t_permission (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  code        VARCHAR(32) NOT NULL UNIQUE,
  name        VARCHAR(64) NOT NULL,
  description VARCHAR(255) NULL,
  customized  BOOLEAN NOT NULL DEFAULT FALSE,   -- 界面是否已配置过（区分「未配」与「配空」）
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

-- 角色 ↔ 权限点（多对多，可配置；留痕）
CREATE TABLE t_role_permission (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_id       BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  granted_by    BIGINT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_role_perm (role_id, permission_id)
);
```

`customized` 是关键字段：`false` = 从未在界面配过 → 走 `defaultRoles()` 兜底；`true` = 界面配过 → 以 `t_role_permission` 为准（**允许为空 = 该权限点对所有人关闭**，用于临时停用某功能）。

---

## 4. 判定与兜底语义

`RoleAuthInterceptor` 的改动点（其余逻辑不变）：

```
1. 解析 JWT → roles
2. 端点有 @RequirePermission(P)?
   ├─ 否 → 走原 @RequireRole 逻辑（完全不变，兼容遗留）
   └─ 是 → 查权限配置（带缓存）：
         customized=false → allowed = P.defaultRoles()      # 代码兜底
         customized=true  → allowed = t_role_permission(P)  # 界面覆盖（可为空）
3. P == PERMISSION_ADMIN → allowed 强制 = {ADMIN}，跳过 DB
4. roles ∩ allowed 非空 → 放行，否则 403
```

**兜底规则的三个不变量**（评审重点）：

- 迁移后**默认行为必须与现状完全一致**：`defaultRoles()` 严格 = 现有 `@RequireRole` 的角色，写单测锁死（对齐 §8.2 + 现状注解，两边互相校验）。
- 「未配置」≠「配空」：靠 `customized` 区分，避免「想关掉某权限却回落到代码默认」的语义漏洞。
- 元权限不可配置：`PERMISSION_ADMIN` 不进 DB，防自锁。

---

## 5. 缓存与一致性

- 权限→角色映射**进程内缓存**（拦截器在每个 `/api/**` 请求都触发，不能每次都查库）。
- 失效策略：**写即失效**（配置保存成功后立即逐出对应权限点缓存），不靠 TTL——降权必须即时生效，否则是安全隐患。
- 多实例部署时：以 DB 为准 + 写即失效；若未来多实例无共享失效通道，退化为极短 TTL（如 10s）兜底。

---

## 6. 前端「权限配置」页

- 位置：左侧「配置」组下新增 **权限配置**（`adminOnly`，仅 ADMIN 可见），沿用刚拆好的菜单结构。
- 形态：一张 **9 权限点 × 4 角色** 的勾选矩阵；行 = 权限点（含描述），列 = DEV/LEAD/DBA/ADMIN。
- 交互：
  - `PERMISSION_ADMIN` 行**灰显、只读**（不参与勾选），提示「仅管理员，不可修改」。
  - 保存 → `POST /api/permissions`（批量 upsert：写 `t_role_permission` + 置 `customized=true` + 写审计 `PERMISSION_ASSIGN` + 失效缓存）。
  - 空勾选 = 关闭该权限点，UI 给出醒目警示。
- 后端新增 `PermissionController`（`@RequireRole(ADMIN)` 或 `@RequirePermission(PERMISSION_ADMIN)`），含 GET（读矩阵，回显未配置项为兜底默认）与 POST（保存）。

---

## 7. 迁移与兼容

| 步骤 | 内容 |
|---|---|
| 1 | 新增 `PermissionCode` 枚举 + `defaultRoles()`（值 = 现状注解） |
| 2 | 新增 `t_permission` / `t_role_permission` 建表 + `PermissionDao` + seed |
| 3 | 新增 `@RequirePermission` 注解 + 拦截器分支（不动旧路径） |
| 4 | 迁移 4 个已强制资源（`USER_ADMIN`/`INSTANCE_ADMIN`/`CONFIG_RULE_FLOW`/`EXECUTE`）为 `@RequirePermission` |
| 5 | 单测锁死：默认行为 == 迁移前；配置覆盖 == 预期 |
| 6 | `PermissionController` + 前端矩阵页 |

未强制 RBAC 的端点（tickets/approval/audit/execution/query）**本次不新增强制**，仅把权限点目录建全、为将来统一 RBAC 铺路——要不要把它们也纳入强制，是另一个需单独评估业务影响的决策（见 §9 待决策）。

---

## 8. 风险与边界

| 风险 | 缓解 |
|---|---|
| **自锁**：把 ADMIN 从 `USER_ADMIN`/`PERMISSION_ADMIN` 移除导致无人能改 | `PERMISSION_ADMIN` 硬编码 ADMIN 永不进 DB；权限配置页自身永不失效 |
| **默认值漂移**：迁移时改动了行为 | `defaultRoles()` 由单测对齐现状注解 + §8.2，双校验 |
| **降权不生效**：缓存旧值导致已降权用户仍能操作 | 写即失效，不依赖 TTL |
| **越权面扩大**：界面把 EXECUTE/CONFIG 授予低权限角色 | 这是功能本身；每次变更写审计 + 保存时二次确认（仿生产环境切换的 confirm） |
| **「配空」语义歧义** | `customized` 字段显式区分，UI 对空配置加警示 |

---

## 9. 待你决策的问题

1. **权限点目录是否就按 §8.2 的 9 个**（含把「实例接入与凭据」合并为 `INSTANCE_ADMIN`）？还是要把「实例接入」与「凭据管理」拆成两个权限点（更细，但现状两者同为 ADMIN，拆开收益有限）？
2. **落地顺序**：先做后端（枚举/表/拦截器，行为不变），再补前端矩阵页，还是一口气全做？
3. **是否顺带把现在「未强制 RBAC」的端点（工单/审批/审计/执行/查询）也纳入权限点强制？** 我建议本次不做，只铺目录，避免一次性扩大改动面。
4. 权限配置页的**菜单位置**：放「配置」组（与规则/流程/实例并列），还是放「用户管理」附近？

---

## 10. 落地点（评审通过后）

- 后端：`sqlaudit-common`（枚举）、`sqlaudit-storage`（dao/建表/seed）、`sqlaudit-server`（注解/拦截器/Controller）
- 前端：`src/views/admin/PermissionConfig.vue` + 路由 + 菜单 + api
- 测试：`PermissionCode` 默认值对齐测试、拦截器覆盖/兜底分支测试
