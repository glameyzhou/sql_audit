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
            style="width: 100%"
          >
            <el-option v-for="ins in INSTANCES" :key="ins.id" :label="ins.name" :value="ins.id" />
          </el-select>
        </el-form-item>

        <el-form-item label="环境" required>
          <el-radio-group v-model="form.environment">
            <el-radio-button v-for="(meta, key) in ENV" :key="key" :value="key">
              {{ meta.label }}
            </el-radio-button>
          </el-radio-group>
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
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../api'
import { ENV, CURRENT_USER, riskMeta, coverageMeta } from '../../constants'

const router = useRouter()

// 开发期假实例（对齐 dev 期约定：id 1/2）
const INSTANCES = [
  { id: 1, name: '生产 MySQL' },
  { id: 2, name: '预发 MySQL' }
]

// 两个假实例均为 MySQL，方言固定；W2 接真实实例元数据后由实例派生
const DIALECT = 'mysql'

const form = ref({
  title: '',
  instanceIds: [],
  environment: 'prod',
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
    !!form.value.environment &&
    form.value.sql.trim().length > 0 &&
    !errorBlocked.value
)

// SQL 变更后作废旧预检结果，避免误用过期 errorBlocked 拦截提交
watch(
  () => form.value.sql,
  () => {
    if (preview.value) preview.value = null
  }
)

async function handlePreview() {
  if (!canPreview.value) return
  previewing.value = true
  try {
    preview.value = await api.preview(form.value.sql, DIALECT)
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
      creatorId: CURRENT_USER.id,
      instanceIds: form.value.instanceIds,
      environment: form.value.environment,
      sql: form.value.sql,
      dialect: DIALECT
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
