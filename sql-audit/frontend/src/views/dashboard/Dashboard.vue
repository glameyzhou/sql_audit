<template>
  <div class="dashboard">
    <!-- 页头 -->
    <div class="page-head">
      <div>
        <div class="page-title">工作台</div>
        <div class="page-desc">我的工单概览与最近动态 · 所有变更与查询均经平台留痕</div>
      </div>
      <div class="head-actions">
        <el-button @click="$router.push('/tickets')">工单中心</el-button>
        <el-button type="primary" @click="$router.push('/tickets/submit')">
          <el-icon style="margin-right: 4px"><Plus /></el-icon>提交变更
        </el-button>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div v-loading="loading" class="stat-grid">
      <el-card
        v-for="card in statCards"
        :key="card.label"
        class="stat-card"
        shadow="never"
      >
        <div class="stat-icon" :style="{ background: card.soft, color: card.color }">
          <el-icon :size="20"><component :is="card.icon" /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-value" :style="{ color: card.valueColor }">{{ card.value }}</div>
          <div class="stat-sub">{{ card.sub }}</div>
        </div>
      </el-card>
    </div>

    <!-- 最近工单 -->
    <el-card class="recent-card" shadow="never">
      <template #header>
        <div class="card-head">
          <div>
            <div class="card-title">最近工单</div>
            <div class="card-sub">点击任意行查看完整链路</div>
          </div>
          <el-button text type="primary" @click="$router.push('/tickets')">
            全部 <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </template>

      <el-table
        :data="recent"
        v-loading="loading"
        class="recent-table"
        :row-class-name="() => 'clickable-row'"
        @row-click="openTicket"
      >
        <el-table-column label="工单 / 标题" min-width="200">
          <template #default="{ row }">
            <div class="cell-mono mono">{{ row.no }}</div>
            <div class="cell-sub">{{ row.title }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag effect="light" :type="statusMeta(row.status).type" size="small">
              {{ statusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="100">
          <template #default="{ row }">
            <el-tag effect="light" :type="riskMeta(row.risk).type" size="small">
              {{ riskMeta(row.risk).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="180">
          <template #default="{ row }">
            <span class="cell-dim">{{ fmtTime(row.time) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-empty
        v-if="!loading && !recent.length"
        :description="error ? '加载失败，请重试' : '暂无工单，去提交第一条变更吧'"
        :image-size="80"
      >
        <el-button v-if="error" type="primary" plain @click="load">重新加载</el-button>
        <el-button v-else type="primary" plain @click="$router.push('/tickets/submit')">提交变更</el-button>
      </el-empty>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, ArrowRight, Tickets, Stamp, Monitor, WarningFilled } from '@element-plus/icons-vue'
import { api } from '../../api'
import { CURRENT_USER, statusMeta, riskMeta } from '../../constants'

const router = useRouter()
const tickets = ref([])
const loading = ref(false)
const error = ref(false)

const total = computed(() => tickets.value.length)
const auditing = computed(() => tickets.value.filter((t) => t.status === 'auditing').length)
const executing = computed(() => tickets.value.filter((t) => t.status === 'executing').length)
const attention = computed(
  () => tickets.value.filter((t) => t.status === 'needs_confirm' || t.status === 'failed').length
)

const statCards = computed(() => [
  { label: '我的工单', value: total.value, sub: '全部提交记录', icon: Tickets, soft: 'var(--sa-brand-soft)', color: 'var(--sa-brand-2)', valueColor: 'var(--sa-brand)' },
  { label: '待审批', value: auditing.value, sub: '审核中（auditing）', icon: Stamp, soft: 'var(--sa-warning-soft)', color: 'var(--sa-warning)', valueColor: 'var(--sa-brand-2)' },
  { label: '执行中', value: executing.value, sub: '正在生产库执行', icon: Monitor, soft: 'var(--sa-info-soft)', color: 'var(--sa-info)', valueColor: 'var(--sa-brand-2)' },
  { label: '需关注', value: attention.value, sub: '待人工确认 / 失败', icon: WarningFilled, soft: 'var(--sa-danger-soft)', color: 'var(--sa-danger)', valueColor: 'var(--sa-brand-2)' }
])

const recent = computed(() =>
  tickets.value.slice(0, 5).map((t) => ({
    id: t.id ?? t.ticketId,
    no: t.ticketNo || t.ticket_no || t.traceId || t.trace_id || t.id,
    title: t.title || '未命名工单',
    status: t.status,
    risk: t.riskLevel ?? t.risk_level ?? t.risk,
    time: t.createdAt ?? t.created_at ?? t.createdTime ?? t.updatedAt ?? t.updated_at
  }))
)

function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').replace(/\.\d+$/, '').replace(/Z$/, '')
}

function openTicket(row) {
  if (row.id !== undefined && row.id !== null) router.push(`/tickets/${row.id}`)
}

async function load() {
  loading.value = true
  error.value = false
  try {
    tickets.value = (await api.listTickets(CURRENT_USER.id)) || []
  } catch {
    error.value = true
    tickets.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.dashboard { display: flex; flex-direction: column; gap: 16px; }

.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 18px; font-weight: 700; color: var(--sa-text); }
.page-desc { font-size: 12.5px; color: var(--sa-text-3); margin-top: 3px; }
.head-actions { display: flex; gap: 8px; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.stat-card :deep(.el-card__body) { display: flex; align-items: center; gap: 14px; padding: 18px; }
.stat-icon {
  width: 44px; height: 44px; flex: none; border-radius: 10px;
  display: grid; place-items: center;
}
.stat-label { font-size: 12px; color: var(--sa-text-2); }
.stat-value { font-size: 26px; font-weight: 700; line-height: 1.2; font-variant-numeric: tabular-nums; }
.stat-sub { font-size: 11px; color: var(--sa-text-3); margin-top: 2px; }

.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-size: 14px; font-weight: 650; color: var(--sa-text); }
.card-sub { font-size: 11.5px; color: var(--sa-text-3); margin-top: 1px; }

.recent-table :deep(.clickable-row) { cursor: pointer; }
.cell-mono { font-family: var(--sa-font-mono); font-size: 12px; color: var(--sa-text); font-weight: 600; }
.cell-sub { font-size: 11px; color: var(--sa-text-3); margin-top: 2px; }
.cell-dim { color: var(--sa-text-3); font-size: 12px; }

@media (max-width: 1000px) {
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
