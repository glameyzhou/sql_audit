<template>
  <div class="page">
    <div class="card">
      <div class="card-head">
        <h3 class="card-title">待审批工单</h3>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
      <p class="hint">
        仅展示「当前审批组、本人可审、非本人提交」的记录；审批人身份取登录账号，防自审由后端硬校验
      </p>

      <div v-loading="loading" class="table-wrap">
        <el-table v-if="list.length" :data="pagedList" class="sa-table">
          <el-table-column label="工单" min-width="220">
            <template #default="{ row }">
              <router-link class="link ticket-title" :to="`/tickets/${row.ticketId}`">
                {{ row.ticketTitle || `工单 ${row.ticketId}` }}
              </router-link>
              <div class="mono ticket-no">TK-{{ row.ticketId }}</div>
            </template>
          </el-table-column>
          <el-table-column label="风险" width="86">
            <template #default="{ row }">
              <el-tag :type="riskMeta(row.riskLevel).type" effect="light" size="small">
                {{ riskMeta(row.riskLevel).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="提交人" min-width="120">
            <template #default="{ row }">
              <span class="dim">{{ row.creatorName || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="节点" min-width="150">
            <template #default="{ row }">
              <span class="node-order">第 {{ row.nodeOrder }} 组</span>
              <span class="node-mode">{{ row.mode === 'AND' ? '会签' : '或签' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="审批人" min-width="160">
            <template #default="{ row }">
              <span class="node-chip">{{ approverTypeLabel(row.approverType) }}</span>
              <span class="dim">{{ row.approverDisplay || row.approverRef }}</span>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" width="170">
            <template #default="{ row }">
              <span class="time">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="240" align="right">
            <template #default="{ row }">
              <template v-if="session.can(API.APPROVAL_DECIDE)">
                <el-button size="small" type="primary" @click="openDecide(row, true)">通过</el-button>
                <el-button size="small" type="danger" @click="openDecide(row, false)">驳回</el-button>
              </template>
              <el-button
                v-if="session.can(API.APPROVAL_TRANSFER)"
                size="small"
                type="warning"
                plain
                @click="openTransfer(row)"
              >转交</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!loading" description="暂无待办审批" />

        <div class="pagination">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="list.length"
            :page-sizes="[10, 20]"
            layout="total, sizes, prev, pager, next"
          />
        </div>
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
          <span class="k">工单</span>
          <span class="v">{{ dialog.row?.ticketTitle || `TK-${dialog.row?.ticketId}` }}</span>
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

    <!-- 审批转交（代理人）弹窗：从用户目录选人 -->
    <el-dialog v-model="transferDialog.visible" title="审批转交（代理人）" width="440px" class="sa-dialog">
      <div class="dlg-body">
        <div class="dlg-kv">
          <span class="k">工单</span>
          <span class="v">{{ transferDialog.row?.ticketTitle || `TK-${transferDialog.row?.ticketId}` }}</span>
        </div>
        <el-select
          v-model="transferDialog.toUserId"
          filterable
          placeholder="选择代理人（转交后由对方审批）"
          style="width: 100%"
        >
          <el-option
            v-for="u in transferableUsers"
            :key="u.id"
            :label="`${u.displayName || u.username}（${u.username}）`"
            :value="u.id"
          />
        </el-select>
        <div class="dlg-tip">
          角色节点的代理人须持有该角色；不能转给提交人或本组已有的审批人（由后端校验）
        </div>
      </div>
      <template #footer>
        <el-button @click="transferDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="transferDialog.submitting" @click="submitTransfer">确认转交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api'
import { API } from '../../api/capabilities'
import { riskMeta, approverTypeLabel } from '../../constants'
import { useSessionStore } from '../../stores/session'

const session = useSessionStore()

function formatTime(v) {
  if (!v) return '-'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return v
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const loading = ref(false)
const list = ref([])
/** 转交候选人（用户目录，仅 ACTIVE） */
const directoryUsers = ref([])

// 客户端分页：待审列表由后端整体下发（仅当前组、本人可审），前端切片展示
const page = ref(1)
const pageSize = ref(10)
const pagedList = computed(() => {
  const from = (page.value - 1) * pageSize.value
  return list.value.slice(from, from + pageSize.value)
})

/** 转交候选：排除自己（转给自己无意义，后端亦会拒） */
const transferableUsers = computed(
  () => directoryUsers.value.filter((u) => u.id !== session.user?.id)
)

async function load() {
  loading.value = true
  try {
    list.value = await api.listPendingApprovals()
    page.value = 1 // 刷新后回到第 1 页
  } catch {
    /* 拦截器已提示错误 */
  } finally {
    loading.value = false
  }
}

async function loadDirectoryUsers() {
  directoryUsers.value = (await api.listDirectoryUsers().catch(() => [])) || []
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
    await api.decide(dialog.row.ticketId, dialog.approve, dialog.comment)
    ElMessage.success(dialog.approve ? '已通过' : '已驳回')
    dialog.visible = false
    await load()
  } catch {
    /* 拦截器已提示错误 */
  } finally {
    dialog.submitting = false
  }
}

// ---- 审批转交（代理人） ----
const transferDialog = reactive({ visible: false, row: null, toUserId: null, submitting: false })

function openTransfer(row) {
  transferDialog.row = row
  transferDialog.toUserId = null
  transferDialog.visible = true
}

async function submitTransfer() {
  if (!transferDialog.toUserId) {
    ElMessage.warning('请选择代理人')
    return
  }
  transferDialog.submitting = true
  try {
    await api.transfer(transferDialog.row.ticketId, transferDialog.toUserId)
    ElMessage.success('已转交')
    transferDialog.visible = false
    await load()
  } catch {
    /* 拦截器已提示错误 */
  } finally {
    transferDialog.submitting = false
  }
}

onMounted(() => {
  load()
  loadDirectoryUsers()
})
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

.pagination { display: flex; justify-content: flex-end; margin-top: 14px; }

.mono { font-family: var(--sa-font-mono); font-size: 12px; }
.link { color: var(--sa-brand-2); }
.link:hover { text-decoration: underline; }
.ticket-title { font-size: 12.5px; font-weight: 600; }
.ticket-no { color: var(--sa-text-3); margin-top: 2px; }
.dim { font-size: 12px; color: var(--sa-text-2); }
.node-order { font-size: 12px; color: var(--sa-text-2); margin-right: 6px; }
.node-mode {
  font-size: 11px; color: var(--sa-text-2); background: var(--sa-surface-3);
  padding: 1px 7px; border-radius: 5px;
}
.node-chip {
  font-size: 11px; color: var(--sa-brand); background: var(--sa-brand-soft);
  padding: 1px 7px; border-radius: 5px; font-weight: 600; margin-right: 6px;
}
.time { font-size: 12px; color: var(--sa-text-2); font-variant-numeric: tabular-nums; }

.dlg-body { display: flex; flex-direction: column; gap: 14px; }
.dlg-kv { display: flex; align-items: center; gap: 10px; font-size: 12px; }
.dlg-kv .k { color: var(--sa-text-3); flex: none; }
.dlg-kv .v { color: var(--sa-text); }
.dlg-tip { font-size: 11.5px; color: var(--sa-text-3); line-height: 1.5; }
</style>
