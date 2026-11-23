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
            v-if="canCancel"
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
          <div class="kv"><span class="k">创建时间</span><span class="v">{{ formatTime(ticket.createdAt) }}</span></div>
          <div class="kv"><span class="k">trace</span><span class="v mono">{{ ticket.traceId || '-' }}</span></div>
          <div class="kv full">
            <span class="k">目标实例</span>
            <div class="v inst-list">
              <div v-if="targetInstance" class="inst-item">
                <span class="inst-name">{{ targetInstance.name }}</span>
                <span class="inst-meta mono">{{ targetInstance.host }}{{ targetInstance.port ? ':' + targetInstance.port : '' }}</span>
                <span class="inst-meta">默认库 {{ targetInstance.defaultSchema || '-' }}</span>
                <DbBadge v-if="targetInstance.dialect" :dialect="targetInstance.dialect" />
                <el-tag v-else size="small" effect="plain" type="info">?</el-tag>
              </div>
              <span v-else class="v">{{ ticket.instanceName || (ticket.instanceId != null ? `实例 ${ticket.instanceId}` : '-') }}</span>
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
            <span class="rb-actions">
              <el-button
                v-if="rollbackOf(i) && session.can(API.ROLLBACKS)"
                link
                type="primary"
                size="small"
                @click="goRollbackSql(rollbackOf(i).id)"
              >查看回滚 SQL</el-button>
              <el-button
                v-else-if="hasPreviewSql(u) && session.can(API.ROLLBACKS)"
                link
                type="primary"
                size="small"
                @click="openPreview(u)"
              >预览回滚 SQL</el-button>
            </span>
          </div>
        </div>
        <div v-else class="rb-empty">暂无回滚清单</div>
      </div>

      <!-- 回滚 SQL 预览弹窗（提交时推导的反向语句模板，非执行后前镜像） -->
      <el-dialog v-model="previewVisible" title="回滚 SQL 预览" width="720px" top="6vh">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          class="preview-alert"
          title="预览语句由提交时的规则推导生成；执行后的实际回滚语句以前镜像为准。"
        />
        <div v-for="(sql, idx) in previewStatements" :key="idx" class="preview-item">
          <div class="preview-no mono">#{{ idx + 1 }}</div>
          <pre class="sql-block mono">{{ sql }}</pre>
        </div>
        <template #footer>
          <el-button @click="previewVisible = false">关闭</el-button>
        </template>
      </el-dialog>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Loading } from '@element-plus/icons-vue'
import { api } from '../../api'
import { API } from '../../api/capabilities'
import { statusMeta, riskMeta, coverageMeta, TERMINAL_TICKET_STATUSES } from '../../constants'
import { useSessionStore } from '../../stores/session'
import DbBadge from '../../components/DbBadge.vue'

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

/** 提交人本人（取消仅限提交人；提交即生成审批节点，无独立提交审批步骤） */
const isCreator = computed(() => !!ticket.value && ticket.value.creatorId === session.user?.id)

// 状态机：可取消 = 提交人 + 非终态（对齐后端 TicketService.cancel 的「任意非终态可取消」语义）
const canCancel = computed(
  () => isCreator.value && !TERMINAL_TICKET_STATUSES.includes(ticket.value.status)
)

// 执行前二次确认：CRITICAL 高危单审批通过后，由持有 approval.confirm 能力的非提交人确认
const canConfirmExecution = computed(
  () => ticket.value
    && ticket.value.status === 'approved'
    && ticket.value.riskLevel === 'CRITICAL'
    && !isCreator.value
    && session.can(API.APPROVAL_CONFIRM)
)

// 只读状态说明（提交人 + 非终态可取消，见 canCancel；此处为各状态的中文提示）
const READONLY_HINTS = {
  auditing: '该工单正在审批中：提交时已生成审批节点，等待审批人处理。',
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
  const t = ticket.value
  if (!t) return '-'
  if (t.creatorName) return t.creatorName
  if (t.creatorId != null && t.creatorId === session.user?.id) return session.displayName || '我'
  return t.creatorId != null ? `用户 ${t.creatorId}` : '-'
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

// 目标实例详情（单实例；拿不到元数据时回退展示工单上的 instanceName / id）
const targetInstance = computed(() => {
  const id = ticket.value?.instanceId
  if (id == null) return null
  return instances.value.find((inst) => inst.id === id) || null
})

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

// 回滚 SQL 预览（rollbackSummary.previewSql：提交时推导的反向语句模板，旧工单可能没有）
const previewVisible = ref(false)
const previewStatements = ref([])

function hasPreviewSql(u) {
  return Array.isArray(u.previewSql) && u.previewSql.length > 0
}

/** 有实际回滚主记录 → 跳回滚管理页自动展开反向语句 */
function goRollbackSql(rollbackId) {
  router.push({ path: '/rollback', query: { rollbackId } })
}

/** 无实际回滚主记录 → 页内弹窗展示提交时推导的预览语句 */
function openPreview(u) {
  previewStatements.value = u.previewSql
  previewVisible.value = true
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
  // 辅助信息（实例 / 单元 / 回滚）失败或无权限时不阻断主信息展示
  const [insts, units, rbs] = await Promise.all([
    session.can(API.INSTANCES) ? api.listUserInstances().catch(() => []) : Promise.resolve([]),
    api.listTicketUnits(route.params.id).catch(() => []),
    session.can(API.ROLLBACKS) ? loadRollbacksForTicket(ticket.value.id) : Promise.resolve([])
  ])
  instances.value = insts
  ticketUnits.value = units
  rollbacks.value = rbs
  loading.value = false
}

/**
 * 拉取本工单的全部回滚主记录（服务端分页 + ticketId 过滤，逐页取全，
 * 供「单元 → 回滚记录」映射；大工单执行单元可能超过单页 200 条上限）。
 */
async function loadRollbacksForTicket(ticketId) {
  const out = []
  const size = 200
  let page = 1
  for (;;) {
    const res = await api.listRollbacks(page, size, ticketId).catch(() => null)
    if (!res || !Array.isArray(res.items) || res.items.length === 0) break
    out.push(...res.items)
    if (out.length >= (res.total || 0) || page > 100) break
    page += 1
  }
  return out
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
    await api.cancelTicket(ticket.value.id)
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
.rb-actions { margin-left: auto; display: flex; align-items: center; gap: 4px; }

/* 回滚 SQL 预览弹窗 */
.preview-alert { margin-bottom: 12px; }
.preview-item { margin-bottom: 12px; }
.preview-no { font-size: 12px; font-weight: 700; color: var(--sa-brand-2); margin-bottom: 4px; }

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
