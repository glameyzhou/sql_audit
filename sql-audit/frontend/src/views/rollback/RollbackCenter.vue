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
        <el-button type="primary" disabled>
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
          <el-table-column label="生成器" width="140">
            <template #default="{ row }">
              <span class="cell-mono mono">{{ row.generator || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="覆盖" width="130">
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
          <el-table-column v-if="canExecute" label="操作" width="140" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="isExecutable(row.status)"
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
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { coverageMeta } from '../../constants'
import { api } from '../../api'
import { useSessionStore } from '../../stores/session'

const session = useSessionStore()
const route = useRoute()

/** 从工单详情跳转带 rollbackId 查询参数时，高亮对应行 */
const highlightId = computed(() => Number(route.query.rollbackId) || null)
function rowClassName({ row }) {
  return highlightId.value && row.id === highlightId.value ? 'highlight-row' : ''
}
/** 执行回滚需 ADMIN/DBA（真实鉴权以后端为准，此处仅控制可见性） */
const canExecute = computed(() => session.roles.includes('ADMIN') || session.roles.includes('DBA'))

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

async function load() {
  loading.value = true
  try {
    rows.value = await api.listRollbacks()
  } catch {
    /* 拦截器已提示错误 */
  } finally {
    loading.value = false
  }
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

onMounted(load)
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

.cell-mono { font-family: var(--sa-font-mono); font-size: 12px; color: var(--sa-text); font-weight: 600; }
.cell-dim { color: var(--sa-text-3); font-size: 12px; font-variant-numeric: tabular-nums; }

/* 从工单详情跳转定位的回滚行高亮 */
:deep(.highlight-row) { background: var(--sa-brand-soft); }
:deep(.highlight-row > td) { background: var(--sa-brand-soft) !important; }
</style>
