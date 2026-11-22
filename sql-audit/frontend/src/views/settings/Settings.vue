<template>
  <div class="settings">
    <!-- 页头 -->
    <div class="page-head">
      <div>
        <div class="page-title">系统配置</div>
        <div class="page-desc">审核规则 · 审批流 · 实例接入（按角色授权增删改，全程写审计）</div>
      </div>
      <span class="role-tag">当前角色：{{ roleLabel }} · {{ canEditAny ? '可编辑' : '只读' }}</span>
    </div>

    <!-- 配置页签 -->
    <el-card class="tabs-card" shadow="never">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <!-- 规则配置 -->
        <el-tab-pane label="规则配置" name="rules">
          <div class="pane-toolbar">
            <span class="toolbar-desc">审核规则引擎 · severity：ERROR 阻断 / WARN 提示</span>
            <el-button
              v-if="canEditRulesFlows"
              type="primary"
              size="small"
              class="toolbar-action"
              @click="openRuleDialog()"
            >新增规则</el-button>
          </div>

          <el-table v-loading="rulesLoading" :data="rules" class="pane-table" empty-text="暂无规则">
            <el-table-column label="规则 code" width="220">
              <template #default="{ row }">
                <span class="cell-mono mono">{{ row.code }}</span>
              </template>
            </el-table-column>
            <el-table-column label="方言" width="120">
              <template #default="{ row }"><span class="cell-dim">{{ row.dialect }}</span></template>
            </el-table-column>
            <el-table-column label="语句类型" width="140">
              <template #default="{ row }"><span class="cell-dim">{{ row.statementType }}</span></template>
            </el-table-column>
            <el-table-column label="级别" width="100">
              <template #default="{ row }">
                <el-tag effect="light" :type="severityType(row.severity)" size="small">
                  {{ row.severity }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="240">
              <template #default="{ row }"><span class="cell-dim">{{ row.description }}</span></template>
            </el-table-column>
            <el-table-column label="启用" width="90" align="center">
              <template #default="{ row }"><el-switch :model-value="!!row.enabled" disabled /></template>
            </el-table-column>
            <el-table-column v-if="canEditRulesFlows" label="操作" width="130" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openRuleDialog(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 审批流配置 -->
        <el-tab-pane label="审批流配置" name="flows">
          <div class="pane-toolbar">
            <span class="toolbar-desc">flow_rule → flow_node · 全局默认 + 实例级覆盖 + 最严链兜底</span>
            <el-button
              v-if="canEditRulesFlows"
              type="primary"
              size="small"
              class="toolbar-action"
              @click="openFlowDialog()"
            >新增流程</el-button>
          </div>

          <el-table
            v-loading="flowsLoading"
            :data="flows"
            class="pane-table"
            empty-text="暂无审批流规则"
            @expand-change="onExpandChange"
          >
            <el-table-column type="expand">
              <template #default="{ row }">
                <div v-loading="!nodesMap[row.id]" class="nodes-panel">
                  <template v-if="nodesMap[row.id] && nodesMap[row.id].length">
                    <div v-for="(n, i) in nodesMap[row.id]" :key="n.id" class="node-row">
                      <span class="node-order">{{ i + 1 }}</span>
                      <span class="node-chip">{{ approverLabel(n.approverType) }}</span>
                      <span class="cell-mono mono">{{ n.approverRef }}</span>
                      <span class="node-mode">{{ n.mode === 'AND' ? '会签' : '或签' }}</span>
                      <span class="cell-dim">超时 {{ n.timeoutMinutes }} 分钟</span>
                    </div>
                  </template>
                  <el-empty
                    v-else-if="nodesMap[row.id]"
                    description="该流程暂无节点"
                    :image-size="48"
                  />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="名称" width="180">
              <template #default="{ row }"><span class="cell-row-title">{{ row.name }}</span></template>
            </el-table-column>
            <el-table-column label="环境" width="100">
              <template #default="{ row }">
                <el-tag effect="light" :type="envType(row.environment)" size="small">
                  {{ envLabel(row.environment) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="风险等级" width="110">
              <template #default="{ row }">
                <el-tag effect="light" :type="riskMeta(row.riskLevel).type" size="small">
                  {{ riskMeta(row.riskLevel).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="优先级" width="90" align="center">
              <template #default="{ row }"><span class="cell-dim">{{ row.priority }}</span></template>
            </el-table-column>
            <el-table-column label="覆盖策略" width="120">
              <template #default="{ row }">
                <el-tag effect="light" :type="row.nonOverridable ? 'danger' : 'info'" size="small">
                  {{ row.nonOverridable ? '不可覆盖' : '允许加严' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="90" align="center">
              <template #default="{ row }"><el-switch :model-value="!!row.enabled" disabled /></template>
            </el-table-column>
            <el-table-column v-if="canEditRulesFlows" label="操作" width="130" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openFlowDialog(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeFlow(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 实例管理 -->
        <el-tab-pane label="实例管理" name="instances">
          <div class="pane-toolbar">
            <span class="toolbar-desc">生产库为核心资产：默认最小权限、按需授权、到期自动回收</span>
            <el-button
              v-if="canEditInstances"
              type="primary"
              size="small"
              class="toolbar-action"
              @click="openInstanceDialog()"
            >新增实例</el-button>
          </div>

          <el-table v-loading="instancesLoading" :data="instances" class="pane-table" empty-text="暂无实例">
            <el-table-column label="数据库" width="200">
              <template #default="{ row }"><span class="cell-row-title">{{ row.name }}</span></template>
            </el-table-column>
            <el-table-column label="连接" min-width="200">
              <template #default="{ row }">
                <span class="cell-mono mono">{{ row.host }}:{{ row.port }}</span>
              </template>
            </el-table-column>
            <el-table-column label="方言" width="110">
              <template #default="{ row }"><span class="cell-dim">{{ row.dialect }}</span></template>
            </el-table-column>
            <el-table-column label="环境" width="100">
              <template #default="{ row }">
                <el-tag effect="light" :type="envType(row.environment)" size="small">
                  {{ envLabel(row.environment) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag effect="light" :type="row.status === 'ACTIVE' ? 'success' : 'warning'" size="small">
                  {{ row.status === 'ACTIVE' ? '正常' : row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="Owner ID" width="110">
              <template #default="{ row }"><span class="cell-dim">{{ row.ownerId }}</span></template>
            </el-table-column>
            <el-table-column label="默认 Schema" min-width="160">
              <template #default="{ row }"><span class="cell-dim">{{ row.defaultSchema || '-' }}</span></template>
            </el-table-column>
            <el-table-column v-if="canEditInstances" label="操作" width="130" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openInstanceDialog(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeInstance(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 用户管理（仅 ADMIN） -->
        <el-tab-pane v-if="isAdmin" label="用户管理" name="users">
          <div class="pane-toolbar">
            <span class="toolbar-desc">本地账号 · 角色分配 · 密码重置（全程写审计）</span>
            <el-button
              type="primary"
              size="small"
              class="toolbar-action"
              @click="openCreateUserDialog()"
            >新增用户</el-button>
          </div>

          <el-table v-loading="usersLoading" :data="users" class="pane-table" empty-text="暂无用户">
            <el-table-column label="用户名" width="160">
              <template #default="{ row }"><span class="cell-mono mono">{{ row.username }}</span></template>
            </el-table-column>
            <el-table-column label="显示名" width="140">
              <template #default="{ row }"><span class="cell-row-title">{{ row.displayName || '-' }}</span></template>
            </el-table-column>
            <el-table-column label="邮箱" min-width="180">
              <template #default="{ row }"><span class="cell-dim">{{ row.email || '-' }}</span></template>
            </el-table-column>
            <el-table-column label="来源" width="90">
              <template #default="{ row }">
                <el-tag effect="light" :type="row.source === 'LOCAL' ? 'info' : 'primary'" size="small">
                  {{ row.source === 'LOCAL' ? '本地' : row.source }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag effect="light" :type="row.status === 'ACTIVE' ? 'success' : 'danger'" size="small">
                  {{ row.status === 'ACTIVE' ? '正常' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="角色" min-width="180">
              <template #default="{ row }">
                <el-tag v-for="r in row.roles" :key="r" effect="light" size="small" class="role-chip">
                  {{ ROLE_LABELS[r] || r }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEditUserDialog(row)">编辑</el-button>
                <el-button link type="warning" size="small" @click="openResetPwdDialog(row)">重置密码</el-button>
                <el-button link type="primary" size="small" @click="openAssignRolesDialog(row)">分配角色</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 规则编辑 dialog -->
    <el-dialog
      v-model="ruleDialogVisible"
      :title="ruleEditingId ? '编辑规则' : '新增规则'"
      width="520px"
      destroy-on-close
    >
      <el-form :model="ruleForm" label-width="96px">
        <el-form-item label="规则 code">
          <el-input v-model="ruleForm.code" placeholder="如 NO_DROP_TABLE" />
        </el-form-item>
        <el-form-item label="方言">
          <el-input v-model="ruleForm.dialect" placeholder="如 MYSQL / PG / ALL" />
        </el-form-item>
        <el-form-item label="语句类型">
          <el-input v-model="ruleForm.statementType" placeholder="如 DDL / DML / DCL" />
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="ruleForm.severity" style="width: 100%">
            <el-option label="ERROR（阻断）" value="ERROR" />
            <el-option label="WARN（提示）" value="WARN" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="ruleForm.enabled" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="ruleForm.description" type="textarea" :rows="3" placeholder="规则说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="ruleSaving" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>

    <!-- 审批流编辑 dialog -->
    <el-dialog
      v-model="flowDialogVisible"
      :title="flowEditingId ? '编辑流程' : '新增流程'"
      width="520px"
      destroy-on-close
    >
      <el-form :model="flowForm" label-width="96px">
        <el-form-item label="名称">
          <el-input v-model="flowForm.name" placeholder="流程名称" />
        </el-form-item>
        <el-form-item label="环境">
          <el-select v-model="flowForm.environment" style="width: 100%">
            <el-option label="开发（dev）" value="dev" />
            <el-option label="测试（test）" value="test" />
            <el-option label="生产（prod）" value="prod" />
          </el-select>
        </el-form-item>
        <el-form-item label="风险等级">
          <el-select v-model="flowForm.riskLevel" style="width: 100%">
            <el-option label="低危" value="LOW" />
            <el-option label="中危" value="MEDIUM" />
            <el-option label="高危" value="HIGH" />
            <el-option label="严重" value="CRITICAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="flowForm.priority" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="不可覆盖">
          <el-switch v-model="flowForm.nonOverridable" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="flowForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="flowDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="flowSaving" @click="saveFlow">保存</el-button>
      </template>
    </el-dialog>

    <!-- 实例编辑 dialog -->
    <el-dialog
      v-model="instanceDialogVisible"
      :title="instanceEditingId ? '编辑实例' : '新增实例'"
      width="520px"
      destroy-on-close
    >
      <el-form :model="instanceForm" label-width="96px">
        <el-form-item label="数据库">
          <el-input v-model="instanceForm.name" placeholder="实例名称" />
        </el-form-item>
        <el-form-item label="方言">
          <el-select v-model="instanceForm.dialect" placeholder="MySQL / PostgreSQL / IoTDB" style="width: 100%">
            <!-- value 与后端 Dialect 枚举一致（MYSQL/PG/IOTDB），POSTGRESQL 等别名不入库 -->
            <el-option label="MySQL" value="MYSQL" />
            <el-option label="PostgreSQL" value="PG" />
            <el-option label="IoTDB" value="IOTDB" />
          </el-select>
        </el-form-item>
        <el-form-item label="环境">
          <el-select v-model="instanceForm.environment" style="width: 100%">
            <el-option label="开发（dev）" value="dev" />
            <el-option label="测试（test）" value="test" />
            <el-option label="生产（prod）" value="prod" />
          </el-select>
        </el-form-item>
        <el-form-item label="Host">
          <el-input v-model="instanceForm.host" placeholder="如 db.example.com" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="instanceForm.port" :min="1" :max="65535" style="width: 100%" />
        </el-form-item>
        <el-form-item label="默认 Schema">
          <el-input v-model="instanceForm.defaultSchema" placeholder="默认 schema" />
        </el-form-item>

        <!-- 测试连接：用户名/密码仅用于本次连通性校验，不随保存提交 -->
        <el-divider content-position="left">测试连接（凭据不随保存提交）</el-divider>
        <el-form-item label="用户名">
          <el-input v-model="testConnForm.username" placeholder="测试连接用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="testConnForm.password" type="password" show-password placeholder="测试连接密码" />
        </el-form-item>
        <el-form-item>
          <el-button :loading="testConnecting" @click="testInstanceConnection">测试连接</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="instanceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="instanceSaving" @click="saveInstance">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增用户 dialog -->
    <el-dialog
      v-model="createUserDialogVisible"
      title="新增用户"
      width="480px"
      destroy-on-close
    >
      <el-form :model="createUserForm" label-width="90px">
        <el-form-item label="用户名" required>
          <el-input v-model="createUserForm.username" placeholder="登录名（唯一）" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="createUserForm.displayName" placeholder="可选，默认同用户名" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="createUserForm.email" placeholder="可选" />
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="createUserForm.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="createUserForm.roles" multiple style="width: 100%" placeholder="选择角色">
            <el-option v-for="r in roleOptions" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createUserDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="userSaving" @click="saveCreateUser">创建</el-button>
      </template>
    </el-dialog>

    <!-- 编辑用户 dialog -->
    <el-dialog
      v-model="editUserDialogVisible"
      title="编辑用户"
      width="440px"
      destroy-on-close
    >
      <el-form :model="editUserForm" label-width="90px">
        <el-form-item label="显示名">
          <el-input v-model="editUserForm.displayName" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="editUserForm.email" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editUserForm.status" style="width: 100%">
            <el-option label="正常（ACTIVE）" value="ACTIVE" />
            <el-option label="停用（DISABLED）" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editUserDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="userSaving" @click="saveEditUser">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 dialog -->
    <el-dialog
      v-model="resetPwdDialogVisible"
      :title="`重置密码：${resetPwdForm.username}`"
      width="400px"
      destroy-on-close
    >
      <el-form :model="resetPwdForm" label-width="90px">
        <el-form-item label="新密码" required>
          <el-input v-model="resetPwdForm.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetPwdDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="userSaving" @click="saveResetPwd">确认重置</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色 dialog -->
    <el-dialog
      v-model="assignRolesDialogVisible"
      :title="`分配角色：${assignRolesForm.username}`"
      width="400px"
      destroy-on-close
    >
      <el-form :model="assignRolesForm" label-width="90px">
        <el-form-item label="角色">
          <el-select v-model="assignRolesForm.roles" multiple style="width: 100%" placeholder="选择角色">
            <el-option v-for="r in roleOptions" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignRolesDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="userSaving" @click="saveAssignRoles">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { riskMeta, ROLE_LABELS, envMeta } from '../../constants'
import { useSessionStore } from '../../stores/session'

const session = useSessionStore()

const roleLabel = computed(
  () => session.roles.map((r) => ROLE_LABELS[r] || r).join('、') || '未知角色'
)

// ---- 权限（真实鉴权以后端为准，此处仅控制可见性）----
const isAdmin = computed(() => session.roles.includes('ADMIN'))
const isDba = computed(() => session.roles.includes('DBA'))
/** 规则 / 审批流：ADMIN 或 DBA 可写 */
const canEditRulesFlows = computed(() => isAdmin.value || isDba.value)
/** 实例：仅 ADMIN 可写 */
const canEditInstances = computed(() => isAdmin.value)
const canEditAny = computed(() => isAdmin.value || isDba.value)

const activeTab = ref('rules')

// ---- 规则 ----
const rules = ref([])
const rulesLoading = ref(false)
let rulesLoaded = false

// ---- 审批流 ----
const flows = ref([])
const flowsLoading = ref(false)
let flowsLoaded = false
const nodesMap = reactive({})

// ---- 实例 ----
const instances = ref([])
const instancesLoading = ref(false)
let instancesLoaded = false

async function loadRules() {
  rulesLoading.value = true
  try {
    rules.value = await api.listRules()
    rulesLoaded = true
  } finally {
    rulesLoading.value = false
  }
}

async function loadFlows() {
  flowsLoading.value = true
  try {
    flows.value = await api.listFlows()
    flowsLoaded = true
  } finally {
    flowsLoading.value = false
  }
}

async function loadNodes(flow) {
  if (nodesMap[flow.id]) return
  const nodes = await api.listFlowNodes(flow.id)
  nodesMap[flow.id] = nodes
}

function onExpandChange(row, expandedRows) {
  if (expandedRows.some((r) => r.id === row.id)) loadNodes(row)
}

async function loadInstances() {
  instancesLoading.value = true
  try {
    instances.value = await api.listInstances()
    instancesLoaded = true
  } finally {
    instancesLoading.value = false
  }
}

function onTabChange(name) {
  if (name === 'rules' && !rulesLoaded) loadRules()
  else if (name === 'flows' && !flowsLoaded) loadFlows()
  else if (name === 'instances' && !instancesLoaded) loadInstances()
  else if (name === 'users' && !usersLoaded) loadUsers()
}

// 首次进入加载当前页签
onTabChange(activeTab.value)

// ---- 规则 CRUD ----
const ruleDialogVisible = ref(false)
const ruleSaving = ref(false)
const ruleEditingId = ref(null)
const ruleForm = reactive({
  code: '',
  dialect: '',
  statementType: '',
  severity: 'WARN',
  enabled: true,
  params: {},
  description: ''
})

function openRuleDialog(row = null) {
  ruleEditingId.value = row ? row.id : null
  Object.assign(ruleForm, {
    code: row?.code || '',
    dialect: row?.dialect || '',
    statementType: row?.statementType || '',
    severity: row?.severity || 'WARN',
    enabled: row ? !!row.enabled : true,
    params: row?.params || {},
    description: row?.description || ''
  })
  ruleDialogVisible.value = true
}

async function saveRule() {
  ruleSaving.value = true
  try {
    const payload = { ...ruleForm }
    if (ruleEditingId.value) {
      await api.updateRule(ruleEditingId.value, payload)
      ElMessage.success('规则已更新')
    } else {
      await api.createRule(payload)
      ElMessage.success('规则已创建')
    }
    ruleDialogVisible.value = false
    await loadRules()
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    ruleSaving.value = false
  }
}

async function removeRule(row) {
  if (!(await confirmDelete(`规则「${row.code}」`))) return
  try {
    await api.deleteRule(row.id)
    ElMessage.success('规则已删除')
    await loadRules()
  } catch {
    // 错误提示已由响应拦截器统一处理
  }
}

// ---- 审批流 CRUD ----
const flowDialogVisible = ref(false)
const flowSaving = ref(false)
const flowEditingId = ref(null)
const flowForm = reactive({
  name: '',
  environment: 'prod',
  riskLevel: 'LOW',
  priority: 0,
  nonOverridable: false,
  enabled: true,
  instanceId: null
})

function openFlowDialog(row = null) {
  flowEditingId.value = row ? row.id : null
  Object.assign(flowForm, {
    name: row?.name || '',
    // 历史数据兼容：旧值 staging 映射为 test
    environment: row?.environment === 'staging' ? 'test' : row?.environment || 'prod',
    riskLevel: row?.riskLevel || 'LOW',
    priority: row?.priority ?? 0,
    nonOverridable: row ? !!row.nonOverridable : false,
    enabled: row ? !!row.enabled : true,
    instanceId: row?.instanceId ?? null
  })
  flowDialogVisible.value = true
}

async function saveFlow() {
  flowSaving.value = true
  try {
    const payload = { ...flowForm }
    if (flowEditingId.value) {
      await api.updateFlow(flowEditingId.value, payload)
      ElMessage.success('流程已更新')
    } else {
      await api.createFlow(payload)
      ElMessage.success('流程已创建')
    }
    flowDialogVisible.value = false
    flowsLoaded = false
    await loadFlows()
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    flowSaving.value = false
  }
}

async function removeFlow(row) {
  if (!(await confirmDelete(`流程「${row.name}」`))) return
  try {
    await api.deleteFlow(row.id)
    ElMessage.success('流程已删除')
    delete nodesMap[row.id]
    flowsLoaded = false
    await loadFlows()
  } catch {
    // 错误提示已由响应拦截器统一处理
  }
}

// ---- 实例 CRUD ----
const instanceDialogVisible = ref(false)
const instanceSaving = ref(false)
const instanceEditingId = ref(null)
// Owner 由后端自动取当前登录用户，前端不再提交 ownerId
const instanceForm = reactive({
  name: '',
  dialect: '',
  environment: 'test',
  host: '',
  port: 3306,
  defaultSchema: '',
  status: 'ACTIVE'
})

// 测试连接：用户名/密码仅用于本次连通性校验，不随保存提交
const testConnForm = reactive({ username: '', password: '' })
const testConnecting = ref(false)

function openInstanceDialog(row = null) {
  instanceEditingId.value = row ? row.id : null
  Object.assign(instanceForm, {
    name: row?.name || '',
    // 历史数据兼容：旧值 POSTGRESQL / staging 分别映射为规范值 PG / test
    dialect: row?.dialect === 'POSTGRESQL' ? 'PG' : row?.dialect || '',
    environment: row?.environment === 'staging' ? 'test' : row?.environment || 'test',
    host: row?.host || '',
    port: row?.port ?? 3306,
    defaultSchema: row?.defaultSchema || '',
    status: row?.status || 'ACTIVE'
  })
  Object.assign(testConnForm, { username: '', password: '' })
  instanceDialogVisible.value = true
}

/** 测试连接：后端返回 { message } 成功提示；失败由响应拦截器统一展示后端 message */
async function testInstanceConnection() {
  if (!instanceForm.host || !instanceForm.port) {
    ElMessage.warning('请先填写 Host 和端口')
    return
  }
  testConnecting.value = true
  try {
    const data = await api.testConnection({
      dialect: instanceForm.dialect,
      host: instanceForm.host,
      port: instanceForm.port,
      username: testConnForm.username,
      password: testConnForm.password,
      database: instanceForm.defaultSchema
    })
    ElMessage.success(data?.message || '连接成功')
  } catch {
    // 失败提示已由响应拦截器统一展示
  } finally {
    testConnecting.value = false
  }
}

async function saveInstance() {
  instanceSaving.value = true
  try {
    const payload = { ...instanceForm }
    if (instanceEditingId.value) {
      await api.updateInstance(instanceEditingId.value, payload)
      ElMessage.success('实例已更新')
    } else {
      await api.createInstance(payload)
      ElMessage.success('实例已创建；凭据（RO_META/RW_EXEC）暂需 SQL 种子，见 README §6.2')
    }
    instanceDialogVisible.value = false
    await loadInstances()
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    instanceSaving.value = false
  }
}

async function removeInstance(row) {
  if (!(await confirmDelete(`实例「${row.name}」`))) return
  try {
    await api.deleteInstance(row.id)
    ElMessage.success('实例已删除')
    await loadInstances()
  } catch {
    // 错误提示已由响应拦截器统一处理
  }
}

// ---- 用户管理（ADMIN）----
const users = ref([])
const usersLoading = ref(false)
let usersLoaded = false
const userSaving = ref(false)
const roleOptions = Object.entries(ROLE_LABELS).map(([code, name]) => ({ code, name }))

async function loadUsers() {
  usersLoading.value = true
  try {
    users.value = await api.listUsers()
    usersLoaded = true
  } finally {
    usersLoading.value = false
  }
}

// 新增用户
const createUserDialogVisible = ref(false)
const createUserForm = reactive({ username: '', displayName: '', email: '', password: '', roles: [] })

function openCreateUserDialog() {
  Object.assign(createUserForm, { username: '', displayName: '', email: '', password: '', roles: [] })
  createUserDialogVisible.value = true
}

async function saveCreateUser() {
  if (!createUserForm.username.trim()) {
    ElMessage.warning('请填写用户名')
    return
  }
  if (!createUserForm.password) {
    ElMessage.warning('请填写初始密码')
    return
  }
  userSaving.value = true
  try {
    await api.createUser({ ...createUserForm })
    ElMessage.success('用户已创建')
    createUserDialogVisible.value = false
    usersLoaded = false
    await loadUsers()
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    userSaving.value = false
  }
}

// 编辑用户（displayName / email / status）
const editUserDialogVisible = ref(false)
const editUserForm = reactive({ id: null, displayName: '', email: '', status: 'ACTIVE' })

function openEditUserDialog(row) {
  Object.assign(editUserForm, {
    id: row.id,
    displayName: row.displayName || '',
    email: row.email || '',
    status: row.status || 'ACTIVE'
  })
  editUserDialogVisible.value = true
}

async function saveEditUser() {
  userSaving.value = true
  try {
    await api.updateUser(editUserForm.id, {
      displayName: editUserForm.displayName,
      email: editUserForm.email,
      status: editUserForm.status
    })
    ElMessage.success('用户已更新')
    editUserDialogVisible.value = false
    await loadUsers()
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    userSaving.value = false
  }
}

// 重置密码
const resetPwdDialogVisible = ref(false)
const resetPwdForm = reactive({ id: null, username: '', password: '' })

function openResetPwdDialog(row) {
  Object.assign(resetPwdForm, { id: row.id, username: row.username, password: '' })
  resetPwdDialogVisible.value = true
}

async function saveResetPwd() {
  if (!resetPwdForm.password) {
    ElMessage.warning('请填写新密码')
    return
  }
  userSaving.value = true
  try {
    await api.resetPassword(resetPwdForm.id, resetPwdForm.password)
    ElMessage.success('密码已重置')
    resetPwdDialogVisible.value = false
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    userSaving.value = false
  }
}

// 分配角色
const assignRolesDialogVisible = ref(false)
const assignRolesForm = reactive({ id: null, username: '', roles: [] })

function openAssignRolesDialog(row) {
  Object.assign(assignRolesForm, {
    id: row.id,
    username: row.username,
    roles: row.roles ? [...row.roles] : []
  })
  assignRolesDialogVisible.value = true
}

async function saveAssignRoles() {
  userSaving.value = true
  try {
    await api.assignRoles(assignRolesForm.id, assignRolesForm.roles)
    ElMessage.success('角色已更新')
    assignRolesDialogVisible.value = false
    await loadUsers()
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    userSaving.value = false
  }
}

/** 删除二次确认（返回是否继续） */
async function confirmDelete(label) {
  try {
    await ElMessageBox.confirm(`确认删除 ${label}？此操作不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    return true
  } catch {
    return false
  }
}

function severityType(severity) {
  if (severity === 'ERROR') return 'danger'
  if (severity === 'WARN') return 'warning'
  return 'info'
}

// 环境标签类型：对齐新 ENV 三档（dev 灰 / test 黄 / prod 红）；staging 旧值兼容显示为「测试」
function envType(env) {
  return envMeta(env).type
}

function envLabel(env) {
  if (env === 'ALL') return '全部'
  return envMeta(env).label
}

function approverLabel(type) {
  return { USER: '用户', ROLE: '角色', INSTANCE_OWNER: '实例 Owner' }[type] || type
}
</script>

<style scoped>
.settings { display: flex; flex-direction: column; gap: 16px; }

.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 18px; font-weight: 700; color: var(--sa-text); }
.page-desc { font-size: 12.5px; color: var(--sa-text-3); margin-top: 3px; }
.role-tag {
  font-size: 11px; color: var(--sa-text-2); background: var(--sa-surface-3);
  padding: 3px 10px; border-radius: 6px; font-weight: 600;
}

.tabs-card :deep(.el-card__body) { padding: 6px 18px 18px; }
.tabs-card :deep(.el-tabs__item) { font-weight: 600; }

.pane-toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin: 12px 0; }
.toolbar-desc { font-size: 12px; color: var(--sa-text-3); }
.toolbar-action { margin-left: auto; }

.cell-mono { font-family: var(--sa-font-mono); font-size: 12px; color: var(--sa-text); font-weight: 600; }
.cell-row-title { font-size: 13px; color: var(--sa-text); font-weight: 600; }
.cell-dim { color: var(--sa-text-2); font-size: 12px; }

.nodes-panel { padding: 8px 12px 8px 8px; min-height: 40px; }
.node-row {
  display: flex; align-items: center; gap: 10px; padding: 5px 0;
  border-bottom: 1px dashed var(--sa-surface-3);
}
.node-row:last-child { border-bottom: none; }
.node-order {
  width: 20px; height: 20px; line-height: 20px; text-align: center;
  border-radius: 50%; background: var(--sa-brand); color: #fff;
  font-size: 11px; font-weight: 700; flex-shrink: 0;
}
.node-chip {
  font-size: 11px; color: var(--sa-brand); background: var(--sa-brand-soft);
  padding: 2px 8px; border-radius: 5px; font-weight: 600;
}
.node-mode {
  font-size: 11px; color: var(--sa-text-2); background: var(--sa-surface-3);
  padding: 2px 8px; border-radius: 5px;
}

.role-chip { margin-right: 4px; }
</style>
