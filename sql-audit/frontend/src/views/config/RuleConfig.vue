<template>
  <div class="config-page">
    <div class="page-head">
      <div>
        <div class="page-title">规则配置</div>
        <div class="page-desc">审核规则引擎 · severity：ERROR 阻断 / WARN 提示</div>
      </div>
      <span class="role-tag">当前角色：{{ session.roleLabel }} · {{ canEditRules ? '可编辑' : '只读' }}</span>
    </div>

    <el-card class="pane-card" shadow="never">
      <div class="pane-toolbar">
        <span class="toolbar-desc">审核规则引擎 · severity：ERROR 阻断 / WARN 提示</span>
        <el-button
          v-if="session.can(API.RULE_CREATE)"
          type="primary"
          size="small"
          class="toolbar-action"
          @click="openRuleDialog()"
        >新增规则</el-button>
      </div>

      <el-table v-loading="rulesLoading" :data="rules" class="pane-table" empty-text="暂无规则">
        <el-table-column label="规则 code" min-width="220">
          <template #default="{ row }">
            <span class="cell-mono mono">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column label="方言" min-width="120">
          <template #default="{ row }">
            <!-- 规则 dialect 含 ALL（不限）与空值：真实方言用徽章，ALL 展示「不限」 -->
            <DbBadge v-if="isRealDialect(row.dialect)" :dialect="row.dialect" />
            <span v-else class="cell-dim">{{ row.dialect === 'ALL' ? '不限' : (row.dialect || '-') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="语句类型" min-width="140">
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
            <el-switch
              v-if="session.can(API.RULE_UPDATE)"
              v-model="row.enabled"
              :loading="row.toggling"
              active-text="开"
              inactive-text="关"
              inline-prompt
              @change="toggleRule(row)"
            />
            <el-switch v-else :model-value="!!row.enabled" disabled active-text="开" inactive-text="关" inline-prompt />
          </template>
        </el-table-column>
        <el-table-column v-if="canEditRules" label="操作" width="130" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="session.can(API.RULE_UPDATE)"
              link
              type="primary"
              size="small"
              @click="openRuleDialog(row)"
            >编辑</el-button>
            <el-button
              v-if="session.can(API.RULE_DELETE)"
              link
              type="danger"
              size="small"
              @click="removeRule(row)"
            >删除</el-button>
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
import { API } from '../../api/capabilities'
import { useSessionStore } from '../../stores/session'
import DbBadge from '../../components/DbBadge.vue'

/** 规则 dialect 含 ALL（不限）与空值，只有真实方言用徽章展示 */
function isRealDialect(d) {
  return ['MYSQL', 'PG', 'IOTDB'].includes(String(d || '').toUpperCase())
}

const session = useSessionStore()

/** 任一写操作可见即显示操作列（真实鉴权以后端为准） */
const canEditRules = computed(
  () => session.can(API.RULE_UPDATE) || session.can(API.RULE_DELETE)
)

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

/**
 * 启用列开关：确认后整行提交（PUT /config/rules/{id} 需要完整规则体）。
 * 后端更新后 reload 规则配置，即时生效，无需重启。
 */
async function toggleRule(row) {
  const target = !!row.enabled
  const action = target ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      target
        ? `确认启用规则 ${row.code}？启用后该规则恢复拦截。`
        : `确认停用规则 ${row.code}？停用后该规则不再拦截对应 SQL，请谨慎操作。`,
      `${action}规则`,
      { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' }
    )
  } catch {
    row.enabled = !target // 用户取消，回滚开关显示
    return
  }
  row.toggling = true
  try {
    await api.updateRule(row.id, { ...row, enabled: target })
    ElMessage.success(`规则已${action}`)
  } catch {
    row.enabled = !target // 请求失败，回滚开关显示
  } finally {
    row.toggling = false
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
