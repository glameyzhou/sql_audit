<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">工单中心</h2>
      <div class="page-actions">
        <el-button type="primary" @click="$router.push('/tickets/submit')">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建提交
        </el-button>
      </div>
    </div>

    <div class="card">
      <el-table v-loading="loading" :data="tickets" row-key="id">
        <el-table-column label="工单号" width="120">
          <template #default="{ row }">
            <span class="mono">TK-{{ row.id }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />

        <el-table-column label="风险" width="86">
          <template #default="{ row }">
            <el-tag :type="riskMeta(row.riskLevel).type" effect="light">
              {{ riskMeta(row.riskLevel).label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="104">
          <template #default="{ row }">
            <el-tag :type="statusMeta(row.status).type" effect="light">
              {{ statusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="环境" width="80">
          <template #default="{ row }">
            <el-tag :type="envMeta(row.environment).type" effect="plain" size="small">
              {{ envMeta(row.environment).label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="实例" min-width="150">
          <template #default="{ row }">
            <span class="instances">{{ instanceNames(row.instanceIds) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="168">
          <template #default="{ row }">
            <span class="time">{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push(`/tickets/${row.id}`)">查看</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无工单，点击右上角「新建提交」创建第一张工单" :image-size="90" />
        </template>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { api } from '../../api'
import { envMeta, riskMeta, statusMeta } from '../../constants'
import { useSessionStore } from '../../stores/session'

// 开发期假实例（与 Submit 保持一致，用于实例列展示）
const INSTANCES = {
  1: '生产 MySQL',
  2: '预发 MySQL'
}

const session = useSessionStore()
const loading = ref(false)
const tickets = ref([])

onMounted(load)

async function load() {
  loading.value = true
  try {
    tickets.value = await api.listTickets(session.user?.id)
  } catch {
    tickets.value = []
  } finally {
    loading.value = false
  }
}

function instanceNames(ids) {
  if (!Array.isArray(ids) || ids.length === 0) return '-'
  return ids.map((id) => INSTANCES[id] || `实例 ${id}`).join('、')
}

function formatTime(value) {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return d.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}
</script>

<style scoped>
.page { max-width: 1120px; }
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 700; color: var(--sa-text); letter-spacing: 0.2px; }
.btn-icon { margin-right: 5px; vertical-align: -2px; }

.card {
  background: var(--sa-surface);
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius);
  box-shadow: var(--sa-shadow);
  padding: 8px 10px;
}

.time { font-size: 12px; color: var(--sa-text-2); font-variant-numeric: tabular-nums; }
.instances { font-size: 12.5px; color: var(--sa-text-2); }
</style>
