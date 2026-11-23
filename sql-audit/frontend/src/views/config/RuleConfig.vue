<template>
  <div class="config-page">
    <div class="page-head">
      <div>
        <div class="page-title">规则配置</div>
        <div class="page-desc">审核规则引擎 · severity：ERROR 阻断 / WARN 提示</div>
      </div>
      <span class="role-tag">当前角色：{{ roleLabel }} · {{ canEditRulesFlows ? '可编辑' : '只读' }}</span>
    </div>

    <el-card class="pane-card" shadow="never">
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
          <template #default="{ row }">
            <el-switch :model-value="!!row.enabled" disabled active-text="开" inactive-text="关" inline-prompt />
          </template>
        </el-table-column>
        <el-table-column v-if="canEditRulesFlows" label="操作" width="130" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRuleDialog(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeRule(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
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
          <el-select v-model="ruleForm.dialect" placeholder="不限" style="width: 100%">
            <el-option label="MySQL" value="MYSQL" />
            <el-option label="PostgreSQL" value="PG" />
            <el-option label="IoTDB" value="IOTDB" />
            <el-option label="不限方言" value="ALL" />
          </el-select>
        </el-form-item>
        <el-form-item label="语句类型">
          <el-select v-model="ruleForm.statementType" placeholder="不限" style="width: 100%">
            <el-option label="SELECT 查询" value="SELECT" />
            <el-option label="INSERT" value="INSERT" />
            <el-option label="UPDATE" value="UPDATE" />
            <el-option label="DELETE" value="DELETE" />
            <el-option label="DDL" value="DDL" />
            <el-option label="不限类型" value="ALL" />
          </el-select>
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="ruleForm.severity" style="width: 100%">
            <el-option label="ERROR（阻断）" value="ERROR" />
            <el-option label="WARN（提示）" value="WARN" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="ruleForm.enabled" active-text="开" inactive-text="关" inline-prompt />
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
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { ROLE_LABELS } from '../../constants'
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

// ---- 规则 ----
const rules = ref([])
const rulesLoading = ref(false)

async function loadRules() {
  rulesLoading.value = true
  try {
    rules.value = await api.listRules()
  } finally {
    rulesLoading.value = false
  }
}

onMounted(loadRules)

// ---- 规则 CRUD ----
const ruleDialogVisible = ref(false)
const ruleSaving = ref(false)
const ruleEditingId = ref(null)
const ruleForm = reactive({
  code: '',
  dialect: 'ALL',
  statementType: 'ALL',
  severity: 'WARN',
  enabled: true,
  params: {},
  description: ''
})

function openRuleDialog(row = null) {
  ruleEditingId.value = row ? row.id : null
  Object.assign(ruleForm, {
    code: row?.code || '',
    dialect: row?.dialect || 'ALL',
    statementType: row?.statementType || 'ALL',
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
</style>
