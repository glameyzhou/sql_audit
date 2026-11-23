<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">工单中心</h2>
      <div class="page-actions">
        <!-- 「全部工单」仅在拥有 ticket.view_all 能力时出现 -->
        <el-radio-group v-if="canViewAll" v-model="scope" size="small" @change="switchScope">
          <el-radio-button value="mine">我的工单</el-radio-button>
          <el-radio-button value="all">全部工单</el-radio-button>
        </el-radio-group>
        <el-button
          v-if="session.can(API.TICKET_CREATE)"
          type="primary"
          @click="$router.push('/tickets/submit')"
        >
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

        <el-table-column v-if="scope === 'all'" label="提交人" min-width="120">
          <template #default="{ row }">
            <span class="creator">{{ row.creatorName || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="实例" min-width="150">
          <template #default="{ row }">
            <span class="instances">{{ row.instanceName || `实例 ${row.instanceId}` }}</span>
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
          <el-empty
            :description="scope === 'all' ? '数据范围内暂无工单' : '暂无工单，点击右上角「新建提交」创建第一张工单'"
            :image-size="90"
          />
        </template>
      </el-table>

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
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { api } from '../../api'
import { API } from '../../api/capabilities'
import { riskMeta, statusMeta } from '../../constants'
import { useSessionStore } from '../../stores/session'

const session = useSessionStore()
const loading = ref(false)
const tickets = ref([])
/** 'mine' = 我提交的；'all' = 数据范围内全部（需 ticket.view_all） */
const scope = ref('mine')
// 服务端分页
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

const canViewAll = computed(() => session.can(API.TICKETS_ALL))

onMounted(load)

/** 切换「我的 / 全部」回到第 1 页 */
function switchScope(value) {
  page.value = 1
  load()
}

async function load() {
  loading.value = true
  try {
    const res = scope.value === 'all'
      ? await api.listAllTickets(page.value, pageSize.value)
      : await api.listTickets(page.value, pageSize.value)
    tickets.value = res?.items || []
    total.value = res?.total || 0
  } catch {
    tickets.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 修改每页条数：回到第 1 页再取数 */
function onSizeChange() {
  page.value = 1
  load()
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
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 700; color: var(--sa-text); letter-spacing: 0.2px; }
.page-actions { display: flex; align-items: center; gap: 12px; }
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
.creator { font-size: 12.5px; color: var(--sa-text-2); }

.pagination { display: flex; justify-content: flex-end; margin-top: 14px; }
</style>
