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
          <div class="kv full"><span class="k">目标实例</span><span class="v">{{ instanceNames(ticket.instanceIds) }}</span></div>
        </div>
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

// 开发期假实例（与 Submit / 列表保持一致）
const INSTANCES = {
  1: '生产 MySQL',
  2: '预发 MySQL'
}

const loading = ref(false)
const notFound = ref(false)
const ticket = ref(null)
const acting = ref(false)

// 状态机：可取消（draft / submitted）
const canCancel = computed(
  () => ticket.value && ['draft', 'submitted'].includes(ticket.value.status)
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

onMounted(load)

async function load() {
  loading.value = true
  try {
    ticket.value = await api.getTicket(route.params.id)
  } catch (err) {
    ticket.value = null
    notFound.value = err?.response?.data?.code === 'TICKET_NOT_FOUND'
  } finally {
    loading.value = false
  }
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

function goBack() {
  router.push('/tickets')
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
</style>
