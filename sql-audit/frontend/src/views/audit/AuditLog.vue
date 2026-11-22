<template>
  <div class="page">
    <!-- 校验结果提示 -->
    <el-alert
      v-if="verify"
      :type="verify.ok ? 'success' : 'error'"
      :closable="false"
      class="verify-alert"
      show-icon
    >
      <template #title>
        <span v-if="verify.ok">审计链完整（{{ verify.checkedCount }} 条）</span>
        <span v-else>链断裂 @seq={{ verify.breakSeq }}：{{ verify.reason }}</span>
      </template>
    </el-alert>

    <div class="card">
      <div class="card-head">
        <h3 class="card-title">审计日志</h3>
        <div class="head-actions">
          <el-button size="small" :loading="verifying" @click="runVerify">校验链完整性</el-button>
          <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>

      <div v-loading="loading" class="table-wrap">
        <el-table v-if="logs.length" :data="logs" class="sa-table">
          <el-table-column label="seq" width="80" align="right">
            <template #default="{ row }">
              <span class="mono">{{ row.seq }}</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">
              <span class="time">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作人" min-width="140">
            <template #default="{ row }">
              <div class="actor">
                <div class="actor-name">{{ row.actorName || row.actorId || '-' }}</div>
                <div class="actor-role">{{ row.actorRole || '' }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="动作" min-width="150">
            <template #default="{ row }">
              <span :class="['action', { 'action-mono': !actionMeta(row.action).known }]">
                {{ actionMeta(row.action).label }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="目标" min-width="150">
            <template #default="{ row }">
              <span class="target">
                <span class="target-type">{{ row.targetType || '-' }}</span>
                <span class="mono target-id">{{ row.targetId || '' }}</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="resultMeta(row.result).type" effect="light" size="small">
                {{ resultMeta(row.result).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="来源 IP" width="130">
            <template #default="{ row }">
              <span class="mono ip">{{ row.clientIp || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="traceId" min-width="150">
            <template #default="{ row }">
              <span class="mono trace">{{ row.traceId || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!loading" description="暂无审计日志" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../../api'

// action 中文映射：已知动作显示中文，其余原样展示（mono）
const ACTION_META = {
  'ticket.state_change': { label: '状态迁移', known: true },
  'query.executed': { label: '查询执行', known: true }
}
function actionMeta(a) {
  return ACTION_META[a] || { label: a || '-', known: false }
}

const RESULT_META = {
  SUCCESS: { label: '成功', type: 'success' },
  success: { label: '成功', type: 'success' },
  FAILURE: { label: '失败', type: 'danger' },
  FAIL: { label: '失败', type: 'danger' },
  failure: { label: '失败', type: 'danger' },
  fail: { label: '失败', type: 'danger' }
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
const logs = ref([])

async function load() {
  loading.value = true
  try {
    logs.value = await api.searchAuditLogs()
  } catch {
    /* 拦截器已提示错误 */
  } finally {
    loading.value = false
  }
}

const verifying = ref(false)
const verify = ref(null)

async function runVerify() {
  if (verifying.value) return
  verifying.value = true
  try {
    verify.value = await api.verifyAudit()
  } catch {
    /* 拦截器已提示错误 */
  } finally {
    verifying.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }

.verify-alert { border-radius: var(--sa-radius-sm); }

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
.head-actions { display: flex; gap: 8px; }

.table-wrap { margin-top: 14px; min-height: 120px; }

.mono { font-family: var(--sa-font-mono); font-size: 12px; }
.time { font-size: 12px; color: var(--sa-text-2); font-variant-numeric: tabular-nums; }

.actor { line-height: 1.3; }
.actor-name { font-size: 12px; font-weight: 600; color: var(--sa-text); }
.actor-role { font-size: 10.5px; color: var(--sa-text-3); }

.action { font-size: 12px; color: var(--sa-text); }
.action-mono { font-family: var(--sa-font-mono); color: var(--sa-text-2); }

.target { display: inline-flex; align-items: center; gap: 6px; }
.target-type { font-size: 10.5px; color: var(--sa-text-3); }
.target-id { color: var(--sa-text-2); }

.ip, .trace { color: var(--sa-text-2); font-size: 11.5px; }
</style>
