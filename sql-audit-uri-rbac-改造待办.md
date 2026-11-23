# URI 匹配式 RBAC 改造 + 审批流指定人 —— 待办交接

> 状态：**分析已完成，代码一行未动**。本文是「回家继续执行」的完整交接。
> 上一份 `sql-audit-rbac-权限可配置化设计.md` 已作废（被本文取代）。

---

## 1. 已拍板的方向（用户原话）

1. **RoleCode 不要出现在系统里了**：角色/权限全部数据化，走 RBAC。
2. **超级管理员跟 ADMIN 无任何关系**：只要一个角色配了「所有 permission」就是超管；`admin` 用户绑定这个角色。
3. **审批流跟 RoleCode 无关系**：可以自己创建一个审批流，比如「提交者 → 张三 → 李四」审批完就能执行。
4. **鉴权走「URI 匹配式」**（已确认）：用户点按钮 → 请求到后端 → 拦截器查「当前 URI + HTTP 方法」对应哪些 permission → 用户的角色是否命中 → 放行/403。**后端不再需要 `@RequirePermission` 注解、不再需要 permission code 枚举。**

> 一句话：`t_permission` 存「URI 模式 + 方法 + 名称」，拦截器按请求 URI 匹配；`t_role_permission` 存角色↔权限映射。加一个权限 = 往 `t_permission` 加一行，不改代码。

---

## 2. 目标（两件事，一起做）

### A. URI 匹配式 RBAC（删掉两个枚举）

- 删除 `RoleCode`、`PermissionCode` 两个枚举。
- `t_permission` 改造为 `(method, uri_pattern, name, description, system)`。
- 删除 `@RequirePermission` 注解；`@RequireRole` → `@RequireLogin`（纯登录标记，无参数）。
- 拦截器：按 `(HTTP method, request URI)` 用 `AntPathMatcher` 匹配 `t_permission`，命中则要求「登录 + 角色命中」，未命中则看 `@RequireLogin`。
- 超级管理员角色（`system=true`）+ 拥有所有 permission + `admin` 用户绑定；防自锁。

### B. 审批流支持指定人（USER 型节点）

- `t_flow_node.approver_type` 已支持 `USER / ROLE / INSTANCE_OWNER`（V1 就建了，只是后端只用 ROLE）。
- 审批链节点从「角色 code 列表」改为「(type, ref) 列表」；USER 节点解析为具体 `user_id`。
- 提供 flow_node 写端点（当前只有读 `GET /flows/{id}/nodes`）。
- 前端审批流编辑：节点可下拉选「人 / 角色 / 实例 Owner」。

---

## 3. 数据模型改动（迁移 V8，三方言 h2/mysql/postgresql）

### 3.1 `t_role` 加 `system`

```sql
ALTER TABLE t_role ADD COLUMN system BOOLEAN NOT NULL DEFAULT FALSE;
-- 内置角色置 system=true（不可删除 / code 不可改）
UPDATE t_role SET system = TRUE WHERE code IN ('DEV','LEAD','DBA','ADMIN');
-- 新增超级管理员角色
INSERT INTO t_role (code, name, description, rank, system)
  VALUES ('SUPER', '超级管理员', '拥有全部权限', 100, TRUE);
```

### 3.2 `t_permission` 改造为 URI 模式

旧列 `code`(UNIQUE) + `customized` 删除；新增 `method` + `uri_pattern` + `system`。

```sql
ALTER TABLE t_permission DROP COLUMN code;
ALTER TABLE t_permission DROP COLUMN customized;
ALTER TABLE t_permission ADD COLUMN method      VARCHAR(8)   NOT NULL DEFAULT '*';
ALTER TABLE t_permission ADD COLUMN uri_pattern VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE t_permission ADD COLUMN system      BOOLEAN      NOT NULL DEFAULT FALSE;
-- 旧 9 权限点数据已无意义，清空后由 SeedPermissionRunner 重灌
DELETE FROM t_role_permission;
DELETE FROM t_permission;
```

> 注意三方言差异：MySQL/H2(MODE=PostgreSQL)/PG 都支持 `DROP COLUMN`；H2 的
> `UNIQUE(code)` 是列内联约束，随列删除。新唯一约束改走
> `CREATE UNIQUE INDEX`（或列内 `UNIQUE(method, uri_pattern)`，PG/H2 索引名全局唯一需消歧）。
> 参考项目既有迁移风格（每方言一份、PG 索引加表名前缀）。

> ⚠️ 迁移序号：用户自己写过 `V4__ticket_sql.sql`、`V6__ticket_shard_config.sql`，本次用 **V8**。

### 3.3 种子：权限目录（`t_permission`，URI 模式）

| method | uri_pattern | name | system |
|---|---|---|---|
| `*` | `/api/roles/**` | 角色管理 | false |
| `*` | `/api/users/**` | 用户管理 | false |
| `*` | `/api/permissions/**` | 权限配置 | **true**（元权限，防自锁） |
| `*` | `/api/config/instances/**` | 实例接入与凭据 | false |
| `*` | `/api/config/rules/**` | 审核规则配置 | false |
| `*` | `/api/config/flows/**` | 审批流配置 | false |
| `POST` | `/api/rollbacks/*/execute` | 回滚执行 | false |
| `*` | `/api/audit-logs/**` | 审计检索/导出 | false |

> 这是「可扩展」的目录起点，覆盖当前受保护端点；后续每加一个按钮/URI 就往表里加一行，无需改代码。

### 3.4 种子：角色（`t_role`）

| code | name | rank | system |
|---|---|---|---|
| SUPER | 超级管理员 | 100 | true |
| DEV | 开发 | 0 | true |
| LEAD | 主管 | 1 | true |
| DBA | DBA | 2 | true |
| ADMIN | 管理员 | 3 | true |

> `SUPER → 全部 permission`（`t_role_permission` 全量），`admin → SUPER`，`dev → DEV`。
> 保留 DEV/LEAD/DBA/ADMIN 是因为默认 L1 审批链（LEAD→DBA→ADMIN）仍按角色 code 引用它们。

### 3.5 防自锁（关键）

- `t_permission.system=true` 的「权限配置」权限**不可删除**。
- `t_role.system=true` 的角色（尤其 SUPER）**不可删除**。
- `PermissionController.save` 加护栏：保存后的矩阵必须**至少有一个角色仍持有「权限配置」**，否则 400 拒绝（防止把所有人从权限管理里踢出去）。
- SUPER 拥有全部权限是「数据」（t_role_permission 全量），不是代码硬编码。

---

## 4. 后端改动清单（按模块，文件 → 改动）

### 4.1 sqlaudit-common（删枚举 + 字面量）

| 文件 | 改动 |
|---|---|
| `enums/RoleCode.java` | **删除整个文件** |
| `enums/PermissionCode.java` | **删除整个文件** |
| `model/AuditEvent.java` | 删掉 `import RoleCode`（注释里引用，改成「角色 code 字符串」描述即可） |
| `test/.../CodedEnumTest.java` | 删掉 `RoleCode.class` / `PermissionCode.class` 相关断言（`wireCodesAreLocked` 里的两行、`codedEnumsAreDiscovered` 数量） |

### 4.2 sqlaudit-storage（模型 + DAO + 内存实现）

| 文件 | 改动 |
|---|---|
| `model/Role.java` | 加 `Boolean system` 字段 + getter/setter |
| `model/Permission.java` | 删除 `code`/`customized`；改为 `method`/`uriPattern`/`name`/`description`/`system` + getter/setter |
| `dao/PermissionDao.java` | 接口改造：`listAll()`、`findByMethodAndUri(method, uri)`、`insert`、`listRoleCodes(id)`、`replaceRolePermissions(id, roleIds, grantedBy)`、**新增 `delete(id)`**（权限 CRUD）。去掉 `findByCode` |
| `dao/RoleDao.java` | `insert`/`update` 需带 `system`；`findByCode` 保留（种子授权用） |
| `impl/jdbc/h2|mysql|postgresql/PermissionDao.java` | 重写：列名 `method/uri_pattern/system`；加 `delete`；`findByMethodAndUri` |
| `impl/jdbc/h2|mysql|postgresql/RoleDao.java` | `insert`/`update` 带 `system` 列；RowMapper 读 `system` |
| `impl/memory/InMemoryPermissionDao.java` | 重写：不再按 PermissionCode 预置，改按 URI 目录种子（见 §3.3）；加 `delete`；`findByMethodAndUri`；维护 `permissionId → roleIds`，角色 code 从 `InMemoryRoleDao` 反查 |
| `impl/memory/InMemoryRoleDao.java` | 种子改 SUPER+DEV+LEAD+DBA+ADMIN（带 system/rank）；删 `RoleCode` import；`grantByCode` 保留 |
| `test/.../SeedDataEnumContractTest.java` | 角色断言从 `RoleCode.values()` 改成期望字面量 `[SUPER,DEV,LEAD,DBA,ADMIN]`；flow_node 的 `RoleCode.fromCode` 校验去掉（改只校验 ApproverType + 角色 code 非空） |

> 各 DAO 的 RowMapper 用 `rs.getString("uri_pattern")` / `rs.getString("method")` / `rs.getBoolean("system")`。

### 4.3 sqlaudit-rule（审批链支持 USER 型）

| 文件 | 改动 |
|---|---|
| `risk/FlowConfig.java` | 删 `RoleCode` import；默认链角色改字符串字面量 `"LEAD"/"DBA"/"ADMIN"`；`resetRoleRanks` 用 `"DEV"/"LEAD"/"DBA"/"ADMIN"/"SUPER"` 字面量 |
| `risk/ApprovalChain.java` | `nodes` 从 `List<String>` 改为 `List<NodeRef>`（新 record `NodeRef(ApproverType type, String ref)`，放在 rule 包或 common）；`strictness()` 里 USER/INSTANCE_OWNER 节点按「rank 0、记 1 个节点」计，ROLE 节点按 roleRank 计 |
| `risk/ApprovalRouter.java` | 适配新 `ApprovalChain`；`fallbackStrictest` 改字面量 |

> `ApproverType` 枚举已存在于 `common/enums`（USER/ROLE/INSTANCE_OWNER），可直接复用。
> `NodeRef` 建议放 `com.sqlaudit.rule.risk`（rule 层，workflow 依赖 rule，不反向）。

### 4.4 sqlaudit-workflow（引擎 USER 型 + 删 RoleCode）

| 文件 | 改动 |
|---|---|
| `ApprovalNode.java` | 保持 `(nodeOrder, approverType, role, mode)`，或加 `ref` 字段承载 USER/INSTANCE_OWNER 的 `user_id`/`*` |
| `ApprovalFlowEngine.java` | 删 `RoleCode` import。`buildNodes` 按 `NodeRef` 生成：USER → `approverId=userId`、`approverRef=username`；ROLE → `approverId=0`、`approverRef=roleCode`；INSTANCE_OWNER → 解析实例 owner。`submitDecision` 的 ROLE 角色校验**只对 ROLE 节点生效**（USER 节点靠 approver_id 精确匹配）。`confirmExecution` 里的 `RoleCode.DBA/ADMIN` 校验**删除**（改由 URI 权限 `POST /api/approvals/*/confirm-execution` 拦截器兜底） |
| `TicketService.java` | 适配 `routeStrictestChain`（链结构变化） |

### 4.5 sqlaudit-server/auth（核心：拦截器 + 权限服务 + 种子）

| 文件 | 改动 |
|---|---|
| `auth/RequirePermission.java` | **删除整个文件** |
| `auth/RequireRole.java` | 改为 `RequireLogin.java`（无 value 的标记注解） |
| `auth/PermissionService.java` | 重写：不再按 PermissionCode；新增 `match(method, uri)` → 命中的权限列表（带缓存）；`isGranted(matchedPermissions, userRoles)` → 布尔；`invalidateAll()` 供保存后失效。缓存整个 `(method, uriPattern, permissionId)` 表 + `permissionId → roleCodes` |
| `auth/RoleAuthInterceptor.java` | 重写：解析 JWT 写 attribute → 调 `permissionService.match(method, path)`；命中→未登录 401 / 角色不符 403；未命中→检查 `@RequireLogin` 标记。用 `AntPathMatcher` |
| `auth/SeedPermissionRunner.java` | 重写：按 §3.3 的 URI 目录 `insert-if-absent`（按 `method+uri_pattern` 判重），不再用 PermissionCode |
| `auth/SeedUserRunner.java` | 重写：`admin → SUPER`（并 grant 全部 permission：`SUPER` 绑定所有 `t_permission` 行）、`dev → DEV`；删 `RoleCode` import |

### 4.6 sqlaudit-server/web（控制器去注解）

| 文件 | 改动 |
|---|---|
| `RoleController.java` | 删 `@RequirePermission(PermissionCode.USER_ADMIN)` 与 import；`isBuiltIn()` 改为「按 `Role.system` 标志」判断（不再 `RoleCode.fromCode`） |
| `UserController.java` | 删 `@RequirePermission`；import |
| `PermissionController.java` | 删 `@RequirePermission`；`matrix()` 改按 URI 权限行输出（`method/uriPattern/name/description/system/roles`）；`save()` 加防自锁护栏；删 `permissionOf()`/`configurable()` 工具 |
| `ConfigController.java` | 删除所有 `@RequirePermission(...)`（实例/规则/流程端点），import 清理 |
| `ApprovalController.java` | `@RequireRole` → `@RequireLogin` |
| `InstanceController.java` | `@RequireRole({...})` → `@RequireLogin` |
| `AuthController.java` | `@RequireRole` → `@RequireLogin` |
| `QueryController.java` | `RoleCode.DEV.getCode()` → 字面量 `"DEV"`（或删掉 actorRole 用 `AuditEvent.SYSTEM_ACTOR_ROLE`） |
| `RollbackController.java` | 删 `@RequirePermission(PermissionCode.EXECUTE)`；import |
| `TicketController.java` | 当前无鉴权（creatorId 来自请求体，注释已标「W2 接 JWT」）；本次加 `@RequireLogin`（**不改 creatorId 来源**，那属另一件事，避免扩大范围） |
| `AuditController.java` / `ExecutionController.java` | 加 `@RequireLogin`（当前完全无鉴权） |
| `service/RollbackExecutor.java` | `RoleCode.DBA.getCode()` → 字面量 `"DBA"`（审计 actorRole 用） |

### 4.7 sqlaudit-server/config

| 文件 | 改动 |
|---|---|
| `FlowConfigLoader.java` | `loadNodes()` 不再只取 ROLE，改读 `(approver_type, approver_ref)` 生成 `NodeRef` 列表，USER/INSTANCE_OWNER 一并纳入链 |
| `AuthWebMvcConfig.java` | 基本不变（拦截器仍拦 `/api/**`，放行 `/api/auth/login`） |

### 4.8 flow_node 写端点（审批流指定人）

`ConfigController` 新增：
- `PUT /api/config/flows/{id}/nodes`：整体替换该流程的节点链（删旧插新），请求体 `List<NodeItem(nodeOrder, approverType, approverRef, mode, timeoutMinutes)`。
- （可选）`GET /api/config/flows/{id}/nodes` 已有。

> `approver_ref`：USER=`user_id` 字符串，ROLE=`role code`，INSTANCE_OWNER=`*`。

---

## 5. 前端改动清单（`sql-audit/frontend/src`）

| 文件 | 改动 |
|---|---|
| `api/index.js` | `listPermissions`/`savePermissions` 契约不变但字段变（`code`→`method/uriPattern`）；新增 `replaceFlowNodes(flowId, nodes)`、`listUsers`（已有）用于选人 |
| `constants.js` | `ROLE_LABELS` 加 `SUPER: '超级管理员'`；去掉对「内置 4 角色」的硬依赖（改由后端 `system` 标志驱动） |
| `views/admin/PermissionConfig.vue` | 行改为「URI 权限」（显示 `method + uri_pattern + name`）；`configurable` 概念删除，改为 `system`（元权限只读）；保存 payload 用 `{ id, roles }` 或 `{ method, uriPattern, roles }` |
| `views/admin/RoleManage.vue` | `isBuiltIn(row.code)` 改 `row.system`；新增角色弹窗允许自由 code（去掉「内置 code 不可改」的 ROLE_LABELS 判断） |
| `views/config/FlowConfig.vue` | 节点编辑支持选「人/角色/实例 Owner」：`approverType` 下拉 + 当 USER 时下拉选用户（`api.listUsers`）+ ROLE 时下拉选角色（`api.listRoles`）；保存走 `replaceFlowNodes` |
| `views/approval/ApprovalCenter.vue` | 待审批列表/转交已有（走 JWT）；无需大改，确认 USER 节点 `approverRef`（username）展示 |
| `stores/session.js` / `views/Layout.vue` / `router/index.js` | 菜单/角色展示去掉对固定 4 角色的硬编码，改后端返回 |

> 前端改完需 `npm run build` 重出 `dist/`。

---

## 6. 测试影响

| 测试 | 处理 |
|---|---|
| `CodedEnumTest` | 删 RoleCode/PermissionCode 断言 |
| `SeedDataEnumContractTest` | 角色种子期望改 `[SUPER,DEV,LEAD,DBA,ADMIN]`；flow_node 不再 `RoleCode.fromCode` |
| `PermissionServiceTest` | 重写为「URI 匹配 + 角色命中」语义（memory 方言） |
| `RoleAdminTest` / `PermissionConfigTest` | 内置角色判定改 `system` 标志；权限矩阵字段改 URI |
| `RiskRouterTest` / `ApprovalFlowEngineTest` / `TicketServiceTest` | 链节点从 `List.of("LEAD")` 改 `List.of(NodeRef(ROLE,"LEAD"))` 等 |
| 集成测试（`SubmissionFlowTest`/`FingerprintCaptureTest`） | 已有 login helper + admin token；确认 admin → SUPER 后角色断言更新 |

---

## 7. 建议执行顺序

1. **迁移 V8（三方言）+ 更新 `SeedDataEnumContractTest`**
2. **storage 层**：`Permission`/`Role` 模型 → `PermissionDao`/`RoleDao` 接口 → 4 套实现（memory/h2/mysql/pg）
3. **common 层**：删 `RoleCode`/`PermissionCode`，改 `AuditEvent`/`QueryController`/`RollbackExecutor` 字面量
4. **rule 层**：`FlowConfig`/`ApprovalChain`(NodeRef)/`ApprovalRouter`
5. **workflow 层**：`ApprovalFlowEngine`(USER 型 + 删 RoleCode)/`ApprovalNode`/`TicketService`
6. **server/auth**：`RequireLogin`、删两注解、`PermissionService`、`RoleAuthInterceptor`、`SeedPermissionRunner`、`SeedUserRunner`
7. **server/config + web**：`FlowConfigLoader`、全部控制器去注解、flow_node 写端点
8. **测试**：全部相关测试适配
9. **`mvn clean test`**（注意：增量编译会藏测试错误，务必 clean）
10. **前端**：改 5 个视图 + api + constants → `npm run build`
11. **端到端验证**：dev profile 起服务，验证 admin(SUPER) 全权限、dev(DEV) 受限、自定义角色勾选 URI 权限、审批流「张三→李四」指定人

---

## 8. git 现状（交接时）

- 分支 `main`，最后提交 `df555da feat: W2 集成收口...`。
- 工作区有**大量未提交改动**（本次会话此前的 RBAC 可配置化/自定义角色/审批链落库等工作，均为暂存/未跟踪）。
- 提交格式：`Co-Authored-By: DeepSeek V4 Pro <glamey.zhou@gmail.com>`。
- 用户要求：全部处理完毕后 `git push` 到远端。
