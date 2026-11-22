<template>
  <div class="monitor">
    <!-- 页头 -->
    <div class="page-head">
      <div>
        <div class="page-title">执行监控</div>
        <div class="page-desc">租约 + 心跳 + 预写 intent + unit_id 幂等确认；异常进 needs_confirm，绝不自动重跑</div>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" disabled>开始执行</el-button>
      </div>
    </div>

    <!-- 筛选器 -->
    <el-card class="filter-card" shadow="never">
      <div class="filter-row">
        <el-select v-model="filterStatus" style="width: 180px" @change="load">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <div class="grow" />
        <span class="filter-hint">共 {{ rows.length }} 个执行单元</span>
      </div>
    </el-card>

    <!-- 执行列表 -->
    <el-card class="list-card" shadow="never">
      <div v-loading="loading" class="table-wrap">
        <el-table v-if="rows.length" :data="rows" class="exec-table">
          <el-table-column label="工单号" width="120">
            <template #default="{ row }">
              <span class="cell-mono mono">{{ row.ticketId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="语句类型" width="130">
            <template #default="{ row }">
              <span class="cell-mono mono">{{ row.statementType || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag effect="light" :type="unitStatusMeta(row.status).type" size="small">
                {{ unitStatusMeta(row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="执行人" width="140">
            <template #default="{ row }">
              <span class="cell-dim">{{ row.leaseOwner || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="执行时间" width="170">
            <template #default="{ row }">
              <span class="cell-dim">{{ formatTime(row.executedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="影响行数" width="100" align="right">
            <template #default="{ row }">
              <span class="cell-mono mono">{{ row.affectedRows ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="错误信息" min-width="220">
            <template #default="{ row }">
              <span class="err">{{ row.errorMessage || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!loading" description="该状态下暂无执行单元" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { api } from '../../api'

// 执行单元状态（对齐后端 UnitStatus 小写 dbValue；与工单 TICKET_STATUS 是两个状态空间）
const UNIT_STATUS = {
  pending: { label: '待执行', type: 'info' },
  leased: { label: '已租约', type: 'info' },
  executing: { label: '执行中', type: 'warning' },
  success: { label: '成功', type: 'success' },
  failed: { label: '失败', type: 'danger' },
  skipped: { label: '已跳过', type: 'info' },
  needs_confirm: { label: '待人工确认', type: 'danger' }
}
function unitStatusMeta(s) {
  return UNIT_STATUS[s] || { label: s || '-', type: 'info' }
}

const statusOptions = [
  { label: '执行中', value: 'executing' },
  { label: '待执行', value: 'pending' },
  { label: '已租约', value: 'leased' },
  { label: '成功', value: 'success' },
  { label: '失败', value: 'failed' },
  { label: '已跳过', value: 'skipped' },
  { label: '待人工确认', value: 'needs_confirm' }
]

function formatTime(v) {
  if (!v) return '-'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return v
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const loading = ref(false)
const rows = ref([])
const filterStatus = ref('executing')

async function load() {
  loading.value = true
  try {
    rows.value = await api.listExecutions(filterStatus.value)
  } catch {
    /* 拦截器已提示错误 */
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.monitor { display: flex; flex-direction: column; gap: 16px; }

.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 18px; font-weight: 700; color: var(--sa-text); }
.page-desc { font-size: 12.5px; color: var(--sa-text-3); margin-top: 3px; }
.head-actions { display: flex; gap: 8px; }

.filter-card :deep(.el-card__body) { padding: 14px 18px; }
.filter-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.grow { flex: 1; }
.filter-hint { font-size: 12px; color: var(--sa-text-3); }

.table-wrap { min-height: 120px; }

.cell-mono { font-family: var(--sa-font-mono); font-size: 12px; color: var(--sa-text); font-weight: 600; }
.cell-dim { color: var(--sa-text-3); font-size: 12px; font-variant-numeric: tabular-nums; }
.err { color: var(--sa-danger); font-size: 12px; display: inline-block; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
