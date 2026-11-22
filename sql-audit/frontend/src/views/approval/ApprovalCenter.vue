<template>
  <div class="page">
    <div class="card">
      <div class="card-head">
        <h3 class="card-title">待审批工单</h3>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
      <p class="hint">审批人由角色解析（W2 后段接登录态，当前以主管 200 模拟）</p>

      <div v-loading="loading" class="table-wrap">
        <el-table v-if="list.length" :data="list" class="sa-table">
          <el-table-column label="工单号" min-width="120">
            <template #default="{ row }">
              <span class="mono strong">{{ row.ticketId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="节点" width="90" align="center">
            <template #default="{ row }">
              <span class="node-order">第 {{ row.nodeOrder }} 节点</span>
            </template>
          </el-table-column>
          <el-table-column label="模式" width="110">
            <template #default="{ row }">
              <span class="mode">{{ row.mode || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="resultMeta(row.result).type" effect="light" size="small">
                {{ resultMeta(row.result).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" width="170">
            <template #default="{ row }">
              <span class="time">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170" align="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="openDecide(row, true)">通过</el-button>
              <el-button size="small" type="danger" @click="openDecide(row, false)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!loading" description="暂无待办审批" />
      </div>
    </div>

    <!-- 审批意见弹窗 -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.approve ? '通过工单' : '驳回工单'"
      width="460px"
      class="sa-dialog"
    >
      <div class="dlg-body">
        <div class="dlg-kv">
          <span class="k">工单号</span>
          <span class="mono v">{{ dialog.row?.ticketId }}</span>
        </div>
        <el-input
          v-model="dialog.comment"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          :placeholder="dialog.approve ? '审批意见（可选）' : '请填写驳回原因'"
        />
      </div>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button
          :type="dialog.approve ? 'primary' : 'danger'"
          :loading="dialog.submitting"
          @click="submitDecide"
        >确认{{ dialog.approve ? '通过' : '驳回' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api'

// 审批结果中文映射（待办列表 result 理论均为 PENDING，兜底通用）
const RESULT_META = {
  PENDING: { label: '待审批', type: 'warning' },
  APPROVED: { label: '已通过', type: 'success' },
  REJECTED: { label: '已驳回', type: 'danger' }
}
function resultMeta(r) {
  return RESULT_META[r] || { label: r || '-', type: 'info' }
}

function formatTime(v) {
  if (!v) return '-'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return v
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const loading = ref(false)
const list = ref([])

async function load() {
  loading.value = true
  try {
    list.value = await api.listPendingApprovals()
  } catch {
    /* 拦截器已提示错误 */
  } finally {
    loading.value = false
  }
}

const dialog = reactive({
  visible: false,
  approve: true,
  row: null,
  comment: '',
  submitting: false
})

function openDecide(row, approve) {
  dialog.row = row
  dialog.approve = approve
  dialog.comment = ''
  dialog.visible = true
}

async function submitDecide() {
  if (dialog.submitting || !dialog.row) return
  dialog.submitting = true
  try {
    await api.decide(dialog.row.ticketId, 200, dialog.approve, dialog.comment)
    ElMessage.success(dialog.approve ? '已通过' : '已驳回')
    dialog.visible = false
    await load()
  } catch {
    /* 拦截器已提示错误 */
  } finally {
    dialog.submitting = false
  }
}

onMounted(load)
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }

.card {
  background: var(--sa-surface);
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius);
  box-shadow: var(--sa-shadow);
  padding: 18px 20px 20px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--sa-border);
}
.card-title { font-size: 13px; font-weight: 650; color: var(--sa-text); }

.hint {
  margin: 12px 0 4px;
  font-size: 12px;
  color: var(--sa-text-3);
}

.table-wrap { margin-top: 12px; min-height: 120px; }

.mono { font-family: var(--sa-font-mono); font-size: 12px; }
.strong { font-weight: 600; color: var(--sa-text); }
.node-order { font-size: 12px; color: var(--sa-text-2); }
.mode { font-size: 12px; color: var(--sa-text-2); }
.time { font-size: 12px; color: var(--sa-text-2); font-variant-numeric: tabular-nums; }

.dlg-body { display: flex; flex-direction: column; gap: 14px; }
.dlg-kv { display: flex; align-items: center; gap: 10px; font-size: 12px; }
.dlg-kv .k { color: var(--sa-text-3); flex: none; }
.dlg-kv .v { color: var(--sa-text); }
</style>
