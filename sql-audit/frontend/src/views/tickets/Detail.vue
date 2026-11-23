<template>
  <div class="page">
    <!-- 加载态 -->
    <div v-if="loading" class="card center">
      <el-icon class="is-loading spin"><Loading /></el-icon>
      <span class="load-txt">加载中…</span>
    </div>

    <!-- 不存在 / 加载失败 -->
    <div v-else-if="!ticket" class="card center">
      <el-empty :description="notFound ? '工单不存在或已被删除' : '加载失败，请稍后重试'" :image-size="96" />
      <el-button @click="goBack">返回工单中心</el-button>
    </div>

    <template v-else>
      <!-- 头部 -->
      <div class="page-head">
        <div class="head-left">
          <el-button link class="back-btn" @click="goBack">
            <el-icon><ArrowLeft /></el-icon>
            工单中心
          </el-button>
          <h2 class="page-title">{{ ticket.title }}</h2>
          <div class="head-tags">
            <el-tag :type="statusMeta(ticket.status).type" effect="light">
              {{ statusMeta(ticket.status).label }}
            </el-tag>
            <el-tag :type="riskMeta(ticket.riskLevel).type" effect="light">
              {{ riskMeta(ticket.riskLevel).label }}
            </el-tag>
          </div>
        </div>

        <div class="page-actions">
          <el-button
            v-if="ticket.status === 'auditing'"
            type="primary"
            :loading="acting"
            @click="handleSubmitApproval"
          >
            提交审批
          </el-button>
          <el-button
            v-else-if="canCancel"
            type="danger"
            plain
            :loading="acting"
            @click="handleCancel"
          >
            取消工单
          </el-button>
          <el-button
            v-if="canConfirmExecution"
            type="warning"
            :loading="acting"
            @click="handleConfirmExecution"
          >
            确认执行
          </el-button>
        </div>
      </div>

      <!-- 只读状态说明 -->
      <el-alert
        v-if="readonlyHint"
        :type="readonlyHintType"
        :closable="false"
        show-icon
        :title="readonlyHint"
        class="hint-alert"
      />

      <!-- 基本信息 -->
      <div class="card">
        <div class="card-title">基本信息</div>
        <div class="kv-grid">
          <div class="kv"><span class="k">工单号</span><span class="v mono">TK-{{ ticket.id }}</span></div>
          <div class="kv"><span class="k">创建人</span><span class="v">{{ creatorName }}</span></div>
          <div class="kv"><span class="k">环境</span><span class="v">
            <el-tag :type="envMeta(ticket.environment).type" effect="plain" size="small">
              {{ envMeta(ticket.environment).label }}
            </el-tag>
          </span></div>
          <div class="kv"><span class="k">创建时间</span><span class="v">{{ formatTime(ticket.createdAt) }}</span></div>
          <div class="kv full">
            <span class="k">目标实例</span>
            <div class="v inst-list">
              <div v-for="inst in targetInstances" :key="inst.id" class="inst-item">
                <span class="inst-name">{{ inst.name }}</span>
                <span class="inst-meta mono">{{ inst.host }}{{ inst.port ? ':' + inst.port : '' }}</span>
                <span class="inst-meta">默认库 {{ inst.defaultSchema || '-' }}</span>
                <el-tag size="small" effect="plain" type="info">{{ inst.dialect || '?' }}</el-tag>
              </div>
              <span v-if="!targetInstances.length" class="v">-</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 变更 SQL -->
      <div class="card">
        <div class="card-title">变更 SQL</div>
        <div v-if="shardInfo" class="shard-banner">
          <el-tag type="info" effect="light" size="small">分表执行</el-tag>
          <span class="shard-banner-text">{{ shardInfo.count }} 张分表（{{ shardInfo.summary }}）</span>
        </div>
        <pre v-if="ticket.sqlText" class="sql-block mono">{{ ticket.sqlText }}</pre>
        <div v-else class="rb-empty">暂无 SQL 内容</div>
      </div>

      <!-- 回滚清单 -->
      <div class="card">
        <div class="card-title">可回滚清单</div>
        <div v-if="rollbackUnits.length" class="rollback-list">
          <div v-for="(u, i) in rollbackUnits" :key="i" class="rb-item">
            <span class="rb-unit mono">#{{ u.unit }}</span>
            <el-tag :type="coverageMeta(u.coverage).type" effect="light" size="small">
              {{ coverageMeta(u.coverage).label }}
            </el-tag>
            <span v-if="u.note" class="rb-note">{{ u.note }}</span>
            <router-link
              v-if="rollbackOf(i)"
              class="rb-link"
              :to="{ path: '/rollback', query: { rollbackId: rollbackOf(i).id } }"
            >查看回滚 #{{ rollbackOf(i).id }}</router-link>
          </div>
        </div>
        <div v-else class="rb-empty">暂无回滚清单</div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Loading } from '@element-plus/icons-vue'
import { api } from '../../api'
import { envMeta, statusMeta, riskMeta, coverageMeta } from '../../constants'
import { useSessionStore } from '../../stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const loading = ref(false)
const notFound = ref(false)
const ticket = ref(null)
const acting = ref(false)

// 辅助信息：实例详情 / 执行单元 / 回滚记录（任一失败不影响主信息展示）
const instances = ref([])
const ticketUnits = ref([])
const rollbacks = ref([])

// 状态机：可取消（draft / submitted）
const canCancel = computed(
  () => ticket.value && ['draft', 'submitted'].includes(ticket.value.status)
)

// 执行前二次确认：CRITICAL 高危单审批通过后，非提交人确认执行（§3.4）
const canConfirmExecution = computed(
  () => ticket.value
    && ticket.value.status === 'approved'
    && ticket.value.riskLevel === 'CRITICAL'
    && ticket.value.creatorId !== session.user?.id
)

// 非操作态只读说明（auditing / draft / submitted 走按钮，其余只读）
const READONLY_HINTS = {
  approved: '该工单已通过审批，等待排期执行。',
  rejected: '该工单已被驳回，如需继续请联系审批人。',
  scheduled: '该工单已排期，等待执行。',
  executing: '该工单正在执行中，请前往「执行监控」查看进度。',
  succeeded: '该工单已执行成功。',
  partially_succeeded: '该工单部分执行成功，请结合回滚清单处理剩余项。',
  failed: '该工单执行失败，请结合回滚清单排查。',
  needs_confirm: '该工单等待人工确认。',
  closed: '该工单已关闭。',
  cancelled: '该工单已取消。',
  expired: '该工单已过期。'
}

const readonlyHint = computed(() => {
  if (!ticket.value) return ''
  return READONLY_HINTS[ticket.value.status] || ''
})

const readonlyHintType = computed(() => {
  if (!ticket.value) return 'info'
  const s = ticket.value.status
  if (['rejected', 'failed', 'needs_confirm'].includes(s)) return 'error'
  if (['partially_succeeded', 'executing'].includes(s)) return 'warning'
  if (s === 'succeeded') return 'success'
  return 'info'
})

const creatorName = computed(() => {
  const c = ticket.value?.creatorId
  const me = session.user
  return c === me?.id ? (me?.displayName || me?.name || `用户 ${c}`) : c != null ? `用户 ${c}` : '-'
})

const rollbackUnits = computed(() => {
  try {
    const arr = JSON.parse(ticket.value?.rollbackSummary || '[]')
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
})

// 分表执行信息（审计可见「分表 DDL」语义）
const shardInfo = computed(() => {
  const raw = ticket.value?.shardConfig
  if (!raw) return null
  try {
    const cfg = typeof raw === 'string' ? JSON.parse(raw) : raw
    const suffixes = Array.isArray(cfg.suffixes) ? cfg.suffixes : []
    const count = suffixes.length
    if (!count) return null
    const summary = count <= 5
      ? suffixes.join(', ')
      : `${suffixes.slice(0, 3).join(', ')} … ${suffixes[count - 1]}`
    return { count, summary, template: cfg.template || '' }
  } catch {
    return null
  }
})

// 实例 id → 实例详情（展示 name / host:port / 默认库 / 方言）
const instanceMap = computed(() => {
  const m = new Map()
  for (const inst of instances.value) m.set(inst.id, inst)
  return m
})

const targetInstances = computed(() =>
  (ticket.value?.instanceIds || [])
    .map((id) => instanceMap.value.get(id))
    .filter(Boolean)
)

// 单元 id → 回滚主记录；rollbackSummary 的 unit 是 1-based 序号，
// 与 listTicketUnits 的 unit_order 稳定排序对齐（下标 i 对应 unit_order i+1）
const rollbackByUnitId = computed(() => {
  const m = new Map()
  for (const r of rollbacks.value) m.set(r.unitId, r)
  return m
})

function rollbackOf(index) {
  const unit = ticketUnits.value[index]
  if (!unit) return null
  return rollbackByUnitId.value.get(unit.id) || null
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    ticket.value = await api.getTicket(route.params.id)
  } catch (err) {
    ticket.value = null
    notFound.value = err?.response?.data?.code === 'TICKET_NOT_FOUND'
    loading.value = false
    return
  }
  // 辅助信息（实例 / 单元 / 回滚）失败不阻断主信息展示
  const [insts, units, rbs] = await Promise.all([
    api.listUserInstances().catch(() => []),
    api.listTicketUnits(route.params.id).catch(() => []),
    api.listRollbacks().catch(() => [])
  ])
  instances.value = insts
  ticketUnits.value = units
  rollbacks.value = rbs
  loading.value = false
}

async function handleSubmitApproval() {
  acting.value = true
  try {
    await api.submitApproval(ticket.value.id, true)
    ElMessage.success('已提交审批')
    await load()
  } catch {
    // 错误已由拦截器统一弹 message
  } finally {
    acting.value = false
  }
}

async function handleCancel() {
  try {
    await ElMessageBox.confirm(
      '取消后工单将进入「已取消」状态，不可恢复。确定取消该工单吗？',
      '取消工单',
      { confirmButtonText: '确定取消', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return // 用户取消
  }
  acting.value = true
  try {
    await api.cancelTicket(ticket.value.id, session.user?.id)
    ElMessage.success('工单已取消')
    await load()
  } catch {
    // 错误已由拦截器统一弹 message
  } finally {
    acting.value = false
  }
}

async function handleConfirmExecution() {
  try {
    await ElMessageBox.confirm(
      '该高危工单执行前需二次确认，确认后 worker 将立即执行。确定确认执行吗？',
      '执行前二次确认',
      { confirmButtonText: '确认执行', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return // 用户取消
  }
  acting.value = true
  try {
    await api.confirmExecution(ticket.value.id)
    ElMessage.success('已确认执行，等待 worker 执行')
    await load()
  } catch {
    // 错误已由拦截器统一弹 message（含 SELF_CONFIRM_FORBIDDEN 防自审）
  } finally {
    acting.value = false
  }
}

function goBack() {
  router.push('/tickets')
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
.page { max-width: 960px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.head-left { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.back-btn { font-size: 12px; color: var(--sa-text-3); }
.page-title { font-size: 18px; font-weight: 700; color: var(--sa-text); letter-spacing: 0.2px; }
.head-tags { display: flex; gap: 6px; }

.card {
  background: var(--sa-surface);
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius);
  box-shadow: var(--sa-shadow);
  padding: 20px 24px;
}
.card + .card { margin-top: 16px; }
.card-title { font-size: 13px; font-weight: 650; color: var(--sa-text); padding-bottom: 12px; margin-bottom: 14px; border-bottom: 1px solid var(--sa-border); }

.hint-alert { margin-bottom: 16px; }

.center { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px 24px; }
.spin { font-size: 22px; color: var(--sa-brand-2); }
.load-txt { font-size: 12px; color: var(--sa-text-3); }

.kv-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 24px; }
.kv { display: flex; gap: 12px; font-size: 12.5px; line-height: 1.5; }
.kv.full { grid-column: 1 / -1; }
.kv .k { flex: none; width: 64px; color: var(--sa-text-3); }
.kv .v { color: var(--sa-text); word-break: break-word; }

.rollback-list { display: flex; flex-direction: column; gap: 8px; }
.rb-item { display: flex; align-items: center; gap: 10px; padding: 8px 10px; background: var(--sa-surface-2); border: 1px solid var(--sa-border); border-radius: var(--sa-radius-sm); }
.rb-unit { font-size: 12px; font-weight: 700; color: var(--sa-brand-2); }
.rb-note { font-size: 12px; color: var(--sa-text-2); }
.rb-empty { font-size: 12px; color: var(--sa-text-3); }
.rb-link { margin-left: auto; font-size: 12px; color: var(--sa-brand-2); }
.rb-link:hover { text-decoration: underline; }

.inst-list { display: flex; flex-direction: column; gap: 6px; }
.inst-item { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.inst-name { font-size: 12.5px; font-weight: 600; color: var(--sa-text); }
.inst-meta { font-size: 12px; color: var(--sa-text-2); }

.sql-block {
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

.shard-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  margin-bottom: 10px;
  background: var(--sa-info-soft);
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius-sm);
}
.shard-banner-text { font-size: 12px; color: var(--sa-text-2); }
</style>
