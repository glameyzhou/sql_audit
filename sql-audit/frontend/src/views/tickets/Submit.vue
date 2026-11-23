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
            v-model="form.instanceId"
            placeholder="选择目标实例"
            filterable
            :loading="instancesLoading"
            style="width: 100%"
          >
            <el-option v-for="ins in userInstances" :key="ins.id" :label="ins.name" :value="ins.id">
              <span>{{ ins.name }}</span>
              <span class="option-meta">
                <DbBadge :dialect="ins.dialect" />
                <span>· 默认库 {{ ins.defaultSchema || '-' }}</span>
              </span>
            </el-option>
            <template #empty>
              <span>暂无可提交的实例（角色数据范围内无实例）</span>
            </template>
          </el-select>
          <div class="form-tip">一张工单只作用于一个实例；审批链按该实例路由（全局链 + 实例专用链）</div>
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

          <!-- 分表执行：DDL 占位符 {sfx} → 展开成所有分表 SQL -->
          <div class="shard-block">
            <div class="shard-toggle">
              <el-switch v-model="shard.enabled" active-text="分表执行" />
              <span class="shard-tip">为 DDL 生成所有分表的 SQL：在表名处写占位符 {sfx}，按数字后缀展开（t_demo{sfx} → t_demo_0、t_demo_1）</span>
            </div>

            <div v-if="shard.enabled" class="shard-config">
              <el-radio-group v-model="shard.mode" size="small">
                <el-radio-button value="range">数字区间</el-radio-button>
                <el-radio-button value="list">自定义列表</el-radio-button>
              </el-radio-group>

              <template v-if="shard.mode === 'range'">
                <el-input-number v-model="shard.range.start" :min="0" size="small" controls-position="right" />
                <span class="shard-sep">～</span>
                <el-input-number v-model="shard.range.end" :min="shard.range.start" size="small" controls-position="right" />
                <span class="shard-label">后缀分隔符</span>
                <el-input v-model="shard.range.sep" size="small" class="shard-suffix-sep" placeholder="数字后缀的前导分隔符，如 _" />
                <span class="shard-label">补零</span>
                <el-select v-model="shard.range.pad" size="small" class="shard-pad">
                  <el-option label="无" :value="0" />
                  <el-option label="2 位" :value="2" />
                  <el-option label="3 位" :value="3" />
                </el-select>
              </template>

              <el-input
                v-else
                v-model="shard.list"
                size="small"
                class="shard-list"
                placeholder="逗号分隔，如 _0,_1,_2,_3"
              />

              <el-button size="small" type="primary" plain @click="generateShardedSql">生成分表 SQL</el-button>
              <span class="shard-tip">生成后写回上方编辑器，走原有「预检 → 提交」</span>
            </div>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button
            v-if="session.can(API.TICKET_PREVIEW)"
            type="primary"
            :disabled="!canPreview"
            :loading="previewing"
            @click="handlePreview"
          >
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
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../api'
import { API } from '../../api/capabilities'
import { riskMeta, coverageMeta } from '../../constants'
import { useSessionStore } from '../../stores/session'
import DbBadge from '../../components/DbBadge.vue'

const router = useRouter()
const session = useSessionStore()

// 实例列表：来自后端（已按当前用户角色的数据范围过滤）
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

// 方言由所选实例派生（无元数据回退 MYSQL）
const selectedDialect = computed(() => {
  const inst = userInstances.value.find((i) => i.id === form.value.instanceId)
  return inst?.dialect || 'MYSQL'
})

const form = ref({
  title: '',
  instanceId: null,
  sql: ''
})

// 分表执行（DDL 分表展开）：占位符 {sfx} → 按后缀规则展开成多语句 SQL
const shard = reactive({
  enabled: false,
  mode: 'range',   // 'range' 数字区间 | 'list' 自定义列表
  range: { start: 0, end: 99, pad: 0, sep: '_' },
  list: ''
})

// 分表配置随工单提交（审计可见「分表 DDL」语义）；SQL 手动改动后失效
const shardConfig = ref(null)
let suppressShardClear = false

const preview = ref(null)
const previewing = ref(false)
const submitting = ref(false)

const canPreview = computed(() => form.value.sql.trim().length > 0)
const errorBlocked = computed(() => !!preview.value?.errorBlocked)
const canSubmit = computed(
  () =>
    form.value.title.trim().length > 0 &&
    form.value.instanceId != null &&
    form.value.sql.trim().length > 0 &&
    !errorBlocked.value
)

// SQL / 目标实例变更后作废旧预检结果，避免误用过期 errorBlocked 拦截提交
watch(
  () => [form.value.sql, form.value.instanceId],
  () => {
    if (preview.value) preview.value = null
    // 手动改动 SQL 后，之前生成的分表配置失效（生成动作通过 suppressShardClear 跳过）
    if (!suppressShardClear) shardConfig.value = null
    suppressShardClear = false
  }
)

// —— 分表执行：{sfx} 占位符展开 ——
function buildSuffixes() {
  if (shard.mode === 'range') {
    const { start, end, pad, sep } = shard.range
    const out = []
    for (let i = start; i <= end; i++) {
      const n = pad > 0 ? String(i).padStart(pad, '0') : String(i)
      out.push(sep + n)
    }
    return out
  }
  return (shard.list || '').split(/[,，\n]/).map((s) => s.trim()).filter(Boolean)
}

function generateShardedSql() {
  const suffixes = buildSuffixes()
  if (!suffixes.length) { ElMessage.warning('请先填写分表后缀'); return }
  if (!form.value.sql.includes('{sfx}')) {
    ElMessage.warning('SQL 中未找到占位符 {sfx}，请在表名处写 {sfx}，如 ALTER TABLE t_order{sfx} ...')
    return
  }
  if (suffixes.length > 500) { ElMessage.warning('分表数量超过 500，请缩小后缀范围'); return }
  const template = form.value.sql
  suppressShardClear = true
  form.value.sql = suffixes.map((s) => template.replaceAll('{sfx}', s)).join(';\n')
  shardConfig.value = { template, suffixes }
  ElMessage.success(`已生成 ${suffixes.length} 条分表 SQL，点「预检」查看逐条解析`)
}

async function handlePreview() {
  if (!canPreview.value) return
  previewing.value = true
  try {
    preview.value = await api.preview(form.value.sql, selectedDialect.value, form.value.instanceId)
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
  if (form.value.sql.includes('{sfx}')) {
    ElMessage.warning('SQL 中还有未展开的分表占位符 {sfx}，请先点击「生成分表 SQL」')
    return
  }
  submitting.value = true
  try {
    // 提交人身份一律取登录态，前端不再传任何身份字段
    const { ticketId } = await api.submitTicket({
      title: form.value.title.trim(),
      instanceId: form.value.instanceId,
      sql: form.value.sql,
      shardConfig: shardConfig.value ? JSON.stringify(shardConfig.value) : null,
      dialect: selectedDialect.value
    })
    ElMessage.success('已提交，进入审批流程')
    router.push(`/tickets/${ticketId}`)
  } catch {
    // ERROR_RULE_BLOCKED / APPROVAL_FLOW_NOT_CONFIGURED 等错误已由拦截器统一弹 message
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
.option-meta { float: right; font-size: 11px; color: var(--sa-text-3); margin-left: 12px; }
.sql-input :deep(.el-textarea__inner) { font-family: var(--sa-font-mono); font-size: 12px; line-height: 1.6; }

/* ---- 分表执行 ---- */
.shard-block { margin-top: 12px; padding-top: 12px; border-top: 1px dashed var(--sa-border); }
.shard-toggle { display: flex; align-items: center; gap: 10px; }
.shard-tip { font-size: 11.5px; color: var(--sa-text-3); }
.shard-config { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.shard-sep { color: var(--sa-text-3); }
.shard-label { font-size: 12px; color: var(--sa-text-2); }
.shard-suffix-sep { width: 130px; }
.shard-pad { width: 80px; }
.shard-list { width: 220px; }

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
