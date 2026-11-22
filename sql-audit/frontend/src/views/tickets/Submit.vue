<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">提交变更</h2>
      <span class="page-sub">编写 SQL → 预检 → 提交工单进入审批链路</span>
    </div>

    <!-- 变更表单 -->
    <div class="card">
      <el-form :model="form" label-width="92px" label-position="left">
        <el-form-item label="工单标题" required>
          <el-input
            v-model="form.title"
            placeholder="一句话描述本次变更，如：为 orders 表增加订单号索引"
            maxlength="120"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="目标实例" required>
          <el-select
            v-model="form.instanceIds"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择目标实例"
            :loading="instancesLoading"
            style="width: 100%"
          >
            <el-option v-for="ins in filteredInstances" :key="ins.id" :label="ins.name" :value="ins.id">
              <span>{{ ins.name }}</span>
              <span class="option-env">{{ envMeta(ins.environment).label }}</span>
            </el-option>
            <template #empty>
              <span>暂无实例，请先在系统配置→实例管理新增</span>
            </template>
          </el-select>
          <div v-if="envFallback" class="form-tip warn">
            当前环境「{{ envInfo.label }}」下暂无实例，已展示全部实例
          </div>
        </el-form-item>

        <el-form-item label="环境" required>
          <el-tag :type="envInfo.type" effect="dark" size="large">{{ envInfo.label }}</el-tag>
          <span class="env-readonly-tip">环境为全局状态，可在顶栏「环境」切换</span>
        </el-form-item>

        <el-form-item label="SQL 语句" required>
          <el-input
            v-model="form.sql"
            type="textarea"
            :rows="9"
            class="sql-input"
            placeholder="支持多语句，用分号分隔。例如：&#10;ALTER TABLE orders ADD INDEX idx_no (order_no);&#10;UPDATE config SET value = 'v2' WHERE key = 'x';"
          />
          <div class="form-tip">
            每条语句将逐条解析、命中审核规则并生成回滚清单；含 ERROR 级规则命中将禁止提交。
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :disabled="!canPreview" :loading="previewing" @click="handlePreview">
            预检
          </el-button>
          <el-button type="primary" plain :disabled="!canSubmit" :loading="submitting" @click="handleSubmit">
            提交工单
          </el-button>
          <span v-if="errorBlocked" class="hint danger">存在 ERROR 级规则命中，禁止提交</span>
        </el-form-item>
      </el-form>
    </div>

    <!-- 预检结果 -->
    <div v-if="preview" class="card preview-card">
      <div class="preview-head">
        <span class="pv-title">预检结果</span>
        <div class="pv-meta">
          <span class="pv-count">{{ preview.unitCount }} 条语句</span>
          <el-tag :type="riskMeta(preview.riskLevel).type" effect="light">
            {{ riskMeta(preview.riskLevel).label }}
          </el-tag>
        </div>
      </div>

      <el-alert
        v-if="preview.errorBlocked"
        type="error"
        :closable="false"
        show-icon
        title="存在 ERROR 级规则命中，禁止提交"
        description="请根据下方规则提示修正 SQL 后重新预检。"
        class="block-alert"
      />

      <div v-for="u in preview.units" :key="u.unitIndex" class="unit">
        <div class="unit-head">
          <span class="unit-index">#{{ u.unitIndex }}</span>
          <span class="unit-type">{{ u.statementType }}</span>
          <el-tag v-if="u.readOnly" type="info" effect="plain" size="small">只读</el-tag>
          <el-tag :type="coverageMeta(u.rollbackPlan?.coverage).type" effect="light" size="small" class="unit-coverage">
            回滚：{{ coverageMeta(u.rollbackPlan?.coverage).label }}
          </el-tag>
        </div>

        <pre class="unit-sql mono">{{ u.statement }}</pre>

        <template v-if="u.ruleHits && u.ruleHits.length">
          <ul class="rule-list">
            <li v-for="(r, i) in u.ruleHits" :key="i" :class="['rule', severityClass(r.severity)]">
              <span class="rule-sev">{{ r.severity }}</span>
              <span class="rule-code mono">{{ r.code }}</span>
              <span class="rule-msg">{{ r.message }}</span>
            </li>
          </ul>
        </template>
        <div v-else class="rule-empty">无规则命中</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../api'
import { ENV, envMeta, riskMeta, coverageMeta } from '../../constants'
import { useSessionStore } from '../../stores/session'

const router = useRouter()
const session = useSessionStore()

// 实例列表：来自后端（按当前登录用户过滤）
const userInstances = ref([])
const instancesLoading = ref(false)

async function loadUserInstances() {
  instancesLoading.value = true
  try {
    userInstances.value = (await api.listUserInstances()) || []
  } catch {
    // 错误已由拦截器统一弹 message
  } finally {
    instancesLoading.value = false
  }
}
onMounted(loadUserInstances)

// 当前全局环境（只读展示，切换入口在顶栏「环境」）
const envInfo = computed(() => ENV[session.env] || ENV.test)

// 按全局环境过滤实例；当前环境无匹配时回退展示全部
const filteredInstances = computed(() => {
  const list = userInstances.value || []
  const matched = list.filter((i) => i.environment === session.env)
  return matched.length ? matched : list
})
const envFallback = computed(() => {
  const list = userInstances.value || []
  return list.length > 0 && !list.some((i) => i.environment === session.env)
})
watch(envFallback, (fallback) => {
  if (fallback) {
    ElMessage.warning(`当前环境「${envInfo.value.label}」下暂无实例，已展示全部实例`)
  }
})

// 方言由所选实例派生（多选时取第一个，无元数据回退 MYSQL）
const selectedDialect = computed(() => {
  const first = userInstances.value.find((i) => form.value.instanceIds.includes(i.id))
  return first?.dialect || 'MYSQL'
})

const form = ref({
  title: '',
  instanceIds: [],
  sql: ''
})

const preview = ref(null)
const previewing = ref(false)
const submitting = ref(false)

const canPreview = computed(() => form.value.sql.trim().length > 0)
const errorBlocked = computed(() => !!preview.value?.errorBlocked)
const canSubmit = computed(
  () =>
    form.value.title.trim().length > 0 &&
    form.value.instanceIds.length > 0 &&
    !!session.env &&
    form.value.sql.trim().length > 0 &&
    !errorBlocked.value
)

// SQL / 目标实例变更后作废旧预检结果，避免误用过期 errorBlocked 拦截提交
watch(
  () => [form.value.sql, form.value.instanceIds],
  () => {
    if (preview.value) preview.value = null
  }
)

async function handlePreview() {
  if (!canPreview.value) return
  previewing.value = true
  try {
    preview.value = await api.preview(form.value.sql, selectedDialect.value, form.value.instanceIds)
  } catch {
    // 错误已由拦截器统一弹 message
  } finally {
    previewing.value = false
  }
}

async function handleSubmit() {
  if (!canSubmit.value) return
  if (preview.value?.errorBlocked) {
    ElMessage.error('存在 ERROR 级规则命中，请先修正 SQL')
    return
  }
  submitting.value = true
  try {
    const { ticketId } = await api.submitTicket({
      title: form.value.title.trim(),
      creatorId: session.user?.id,
      instanceIds: form.value.instanceIds,
      environment: session.env,
      sql: form.value.sql,
      dialect: selectedDialect.value
    })
    ElMessage.success('工单创建成功')
    router.push(`/tickets/${ticketId}`)
  } catch {
    // ERROR_RULE_BLOCKED 等错误已由拦截器统一弹 message
  } finally {
    submitting.value = false
  }
}

function severityClass(severity) {
  const s = (severity || '').toUpperCase()
  return s === 'ERROR' ? 'error' : 'warn'
}
</script>

<style scoped>
.page { max-width: 1080px; }
.page-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 700; color: var(--sa-text); letter-spacing: 0.2px; }
.page-sub { font-size: 12px; color: var(--sa-text-3); }

.card {
  background: var(--sa-surface);
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius);
  box-shadow: var(--sa-shadow);
  padding: 22px 24px;
}
.card + .card { margin-top: 18px; }

.form-tip { font-size: 11.5px; color: var(--sa-text-3); line-height: 1.5; margin-top: 6px; }
.form-tip.warn { color: var(--sa-warning); }
.option-env { float: right; font-size: 11px; color: var(--sa-text-3); margin-left: 12px; }
.env-readonly-tip { font-size: 11.5px; color: var(--sa-text-3); margin-left: 10px; }
.sql-input :deep(.el-textarea__inner) { font-family: var(--sa-font-mono); font-size: 12px; line-height: 1.6; }

.hint.danger { font-size: 12px; color: var(--sa-danger); margin-left: 4px; }

/* ---- 预检结果 ---- */
.preview-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.pv-title { font-size: 13.5px; font-weight: 650; color: var(--sa-text); }
.pv-meta { display: flex; align-items: center; gap: 10px; }
.pv-count { font-size: 12px; color: var(--sa-text-2); }

.block-alert { margin-bottom: 14px; }

.unit { border-top: 1px solid var(--sa-border); padding: 14px 0 4px; }
.unit:first-of-type { border-top: none; padding-top: 0; }
.unit-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.unit-index { font-family: var(--sa-font-mono); font-size: 12px; font-weight: 700; color: var(--sa-brand-2); }
.unit-type { font-size: 12px; color: var(--sa-text-2); }
.unit-coverage { margin-left: auto; }

.unit-sql {
  font-size: 12px;
  line-height: 1.6;
  color: var(--sa-text);
  background: var(--sa-surface-2);
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius-sm);
  padding: 10px 12px;
  margin: 0 0 10px;
  white-space: pre-wrap;
  word-break: break-word;
}

.rule-list { list-style: none; margin: 0 0 6px; }
.rule { display: flex; align-items: flex-start; gap: 8px; padding: 6px 0; }
.rule-sev {
  flex: none;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.3px;
  border-radius: 4px;
  padding: 1px 6px;
}
.rule.error .rule-sev { color: var(--sa-danger); background: var(--sa-danger-soft); }
.rule.warn .rule-sev { color: var(--sa-warning); background: var(--sa-warning-soft); }
.rule-code { flex: none; font-size: 12px; color: var(--sa-text-3); padding-top: 1px; }
.rule-msg { font-size: 12px; color: var(--sa-text-2); line-height: 1.5; }
.rule-empty { font-size: 12px; color: var(--sa-text-3); padding-bottom: 6px; }
</style>
