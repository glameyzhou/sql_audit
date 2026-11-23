<template>
  <div class="rollback">
    <!-- 页头 -->
    <div class="page-head">
      <div>
        <div class="page-title">回滚管理</div>
        <div class="page-desc">回滚不是事后安慰剂：有效期 · 依赖检测 · 倒序 · 独立审批 · 幂等</div>
      </div>
      <div class="head-actions">
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button v-if="canExecute" type="primary" disabled>
          <el-icon style="margin-right: 4px"><Plus /></el-icon>新建回滚
        </el-button>
      </div>
    </div>

    <!-- 回滚记录 -->
    <el-card class="list-card" shadow="never">
      <template #header>
        <div class="card-head">
          <div>
            <div class="card-title">回滚记录</div>
            <div class="card-sub">基于前镜像 hash 的依赖检测是执行回滚的硬性前置</div>
          </div>
        </div>
      </template>

      <div v-loading="loading" class="table-wrap">
        <el-table v-if="rows.length" :data="rows" class="rollback-table" :row-class-name="rowClassName">
          <el-table-column label="回滚单号" width="120">
            <template #default="{ row }">
              <span class="cell-mono mono">{{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column label="关联单元" width="120">
            <template #default="{ row }">
              <span class="cell-mono mono">{{ row.unitId ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="生成器" min-width="140">
            <template #default="{ row }">
              <span class="cell-mono mono">{{ row.generator || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="覆盖" min-width="130">
            <template #default="{ row }">
              <el-tag effect="light" :type="coverageMeta(row.coverage).type" size="small">
                {{ coverageMeta(row.coverage).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag effect="light" :type="rbStatusMeta(row.status).type" size="small">
                {{ rbStatusMeta(row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="有效期" min-width="180">
            <template #default="{ row }">
              <span class="cell-dim">{{ formatTime(row.expiresAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="180">
            <template #default="{ row }">
              <span class="cell-dim">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="canView" label="操作" width="230" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                size="small"
                :loading="sqlLoading && sqlRollback?.id === row.id"
                @click="openSql(row)"
              >回滚 SQL</el-button>
              <el-button
                v-if="row.ticketId != null"
                link
                type="primary"
                size="small"
                @click="goTicket(row)"
              >工单详情</el-button>
              <el-button
                v-if="canExecute && isExecutable(row.status)"
                link
                type="danger"
                size="small"
                :loading="executingId === row.id"
                @click="execute(row)"
              >执行回滚</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!loading" description="暂无回滚记录" />

        <div class="pagination">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="load"
            @size-change="onSizeChange"
          />
        </div>
      </div>
    </el-card>

    <!-- 回滚 SQL 弹窗（反向语句 + 前镜像旧值，展示用） -->
    <el-dialog
      v-model="sqlVisible"
      :title="sqlRollback ? `回滚 SQL · 回滚单 #${sqlRollback.id}` : '回滚 SQL'"
      width="760px"
      top="6vh"
    >
      <div v-loading="sqlLoading" class="sql-dialog-body">
        <template v-if="sqlStatements.length">
          <div v-for="(stmt, idx) in sqlStatements" :key="stmt.id ?? idx" class="stmt-item">
            <div class="stmt-no mono">#{{ idx + 1 }}</div>
            <pre class="stmt-sql mono">{{ stmt.sql }}</pre>
          </div>
        </template>
        <div v-else-if="!sqlLoading" class="sql-empty">暂无可展示的回滚 SQL</div>
      </div>
      <template #footer>
        <el-button @click="sqlVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { coverageMeta } from '../../constants'
import { api } from '../../api'
import { API } from '../../api/capabilities'
import { useSessionStore } from '../../stores/session'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()

/** 从工单详情跳转带 rollbackId 查询参数时，高亮对应行 */
const highlightId = computed(() => Number(route.query.rollbackId) || null)
function rowClassName({ row }) {
  return highlightId.value && row.id === highlightId.value ? 'highlight-row' : ''
}
/** 查看回滚 SQL 需 rollback.view 能力（真实鉴权以后端为准，此处仅控制可见性） */
const canView = computed(() => session.can(API.ROLLBACKS))
/** 执行回滚需 rollback.execute 能力（真实鉴权以后端为准，此处仅控制可见性） */
const canExecute = computed(() => session.can(API.ROLLBACK_EXECUTE))

// 回滚 SQL 生命周期（对齐后端 RollbackStatus 小写 dbValue）
const RB_STATUS = {
  generated: { label: '待校验', type: 'info' },
  verified: { label: '已校验', type: 'success' },
  applied: { label: '已执行', type: 'primary' },
  failed: { label: '执行失败', type: 'danger' },
  partial: { label: '部分执行', type: 'warning' },
  expired: { label: '已过期', type: 'info' },
  not_available: { label: '不可回滚', type: 'danger' }
}
function rbStatusMeta(s) {
  return RB_STATUS[s] || { label: s || '-', type: 'info' }
}

/** 仅 generated / verified 可执行回滚 */
function isExecutable(status) {
  return status === 'generated' || status === 'verified'
}

function formatTime(v) {
  if (!v) return '-'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return v
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const loading = ref(false)
const rows = ref([])
const executingId = ref(null)
// 服务端分页
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

// 回滚 SQL 弹窗（反向语句 + 前镜像旧值，展示用）
const sqlVisible = ref(false)
const sqlLoading = ref(false)
const sqlRollback = ref(null)
const sqlStatements = ref([])

async function load() {
  loading.value = true
  try {
    const res = await api.listRollbacks(page.value, pageSize.value)
    rows.value = res?.items || []
    total.value = res?.total || 0
  } catch {
    /* 拦截器已提示错误 */
  } finally {
    loading.value = false
  }
}

/** 修改每页条数：回到第 1 页再取数 */
function onSizeChange() {
  page.value = 1
  load()
}

/** 打开某条回滚的反向语句弹窗（含从工单详情跳转的自动展开） */
async function openSql(row) {
  sqlRollback.value = row
  sqlStatements.value = []
  sqlVisible.value = true
  sqlLoading.value = true
  try {
    sqlStatements.value = await api.listRollbackStatements(row.id)
  } catch {
    sqlStatements.value = []
  } finally {
    sqlLoading.value = false
  }
}

/** 跳转工单详情（后端下发的 ticketId，历史脏数据可能缺省时不显示入口） */
function goTicket(row) {
  router.push(`/tickets/${row.ticketId}`)
}

async function execute(row) {
  try {
    await ElMessageBox.confirm(
      `确认执行回滚 #${row.id}？反向语句将真正应用到生产库，此操作不可撤销。`,
      '执行回滚',
      { type: 'warning', confirmButtonText: '执行', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  executingId.value = row.id
  try {
    const result = await api.executeRollback(row.id)
    if (result.status === 'applied') {
      ElMessage.success(`回滚 #${row.id} 已执行，应用 ${result.applied} 条`)
    } else {
      ElMessage.warning(`回滚 #${row.id} 状态 ${result.status}，已应用 ${result.applied} 条`)
    }
    await load()
  } catch {
    /* 拦截器已提示错误 */
  } finally {
    executingId.value = null
  }
}

onMounted(async () => {
  await load()
  // 从工单详情「查看回滚 SQL」跳转时，自动展开对应回滚的反向语句弹窗。
  // 服务端分页后目标回滚可能不在当前页：行在则正常打开（含行高亮），
  // 不在则用最小 stub（仅 id）直接拉反向语句，保证跳转行为不因分页失效。
  const targetId = highlightId.value
  if (targetId) {
    const row = rows.value.find((r) => r.id === targetId)
    if (row) {
      await openSql(row)
    } else {
      await openSql({ id: targetId })
    }
  }
})
</script>

<style scoped>
.rollback { display: flex; flex-direction: column; gap: 16px; }

.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 18px; font-weight: 700; color: var(--sa-text); }
.page-desc { font-size: 12.5px; color: var(--sa-text-3); margin-top: 3px; }
.head-actions { display: flex; gap: 8px; }

.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-size: 14px; font-weight: 650; color: var(--sa-text); }
.card-sub { font-size: 11.5px; color: var(--sa-text-3); margin-top: 1px; }

.table-wrap { min-height: 120px; }

.pagination { display: flex; justify-content: flex-end; margin-top: 14px; }

.cell-mono { font-family: var(--sa-font-mono); font-size: 12px; color: var(--sa-text); font-weight: 600; }
.cell-dim { color: var(--sa-text-3); font-size: 12px; font-variant-numeric: tabular-nums; }

/* 从工单详情跳转定位的回滚行高亮 */
:deep(.highlight-row) { background: var(--sa-brand-soft); }
:deep(.highlight-row > td) { background: var(--sa-brand-soft) !important; }

/* 回滚 SQL 弹窗：逐条编号 + 代码块 */
.sql-dialog-body { min-height: 80px; max-height: 60vh; overflow: auto; }
.stmt-item { margin-bottom: 12px; }
.stmt-no { font-size: 12px; font-weight: 700; color: var(--sa-brand-2); margin-bottom: 4px; }
.stmt-sql {
  margin: 0;
  padding: 12px 14px;
  background: var(--sa-surface-2);
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius-sm);
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--sa-text);
}
.sql-empty { padding: 24px 0; text-align: center; font-size: 12px; color: var(--sa-text-3); }
</style>
