<template>
  <div class="config-page">
    <div class="page-head">
      <div>
        <div class="page-title">审批流配置</div>
        <div class="page-desc">flow_rule → flow_node · 全局默认 + 实例级覆盖 + 最严链兜底</div>
      </div>
      <span class="role-tag">当前角色：{{ roleLabel }} · {{ canEditRulesFlows ? '可编辑' : '只读' }}</span>
    </div>

    <el-card class="pane-card" shadow="never">
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
        <el-table-column label="名称" min-width="180">
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
          <template #default="{ row }">
            <el-switch :model-value="!!row.enabled" disabled active-text="开" inactive-text="关" inline-prompt />
          </template>
        </el-table-column>
        <el-table-column v-if="canEditRulesFlows" label="操作" width="130" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openFlowDialog(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeFlow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

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
          <el-switch v-model="flowForm.nonOverridable" active-text="开" inactive-text="关" inline-prompt />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="flowForm.enabled" active-text="开" inactive-text="关" inline-prompt />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="flowDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="flowSaving" @click="saveFlow">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
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

// ---- 审批流 ----
const flows = ref([])
const flowsLoading = ref(false)
const nodesMap = reactive({})

async function loadFlows() {
  flowsLoading.value = true
  try {
    flows.value = await api.listFlows()
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

onMounted(loadFlows)

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
    await loadFlows()
  } catch {
    // 错误提示已由响应拦截器统一处理
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

// 环境标签类型：对齐 ENV 三档（dev 灰 / test 黄 / prod 红）；staging 旧值兼容显示为「测试」
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
.config-page { display: flex; flex-direction: column; gap: 16px; }

.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 18px; font-weight: 700; color: var(--sa-text); }
.page-desc { font-size: 12.5px; color: var(--sa-text-3); margin-top: 3px; }
.role-tag {
  font-size: 11px; color: var(--sa-text-2); background: var(--sa-surface-3);
  padding: 3px 10px; border-radius: 6px; font-weight: 600;
}

.pane-card :deep(.el-card__body) { padding: 8px 18px 18px; }

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
</style>
