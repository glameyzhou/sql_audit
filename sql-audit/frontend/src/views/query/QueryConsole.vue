<template>
  <div class="page">
    <div class="card">
      <div class="card-head">
        <h3 class="card-title">线上查询</h3>
      </div>

      <el-form label-position="top" class="q-form">
        <div class="form-row">
          <el-form-item label="目标实例">
            <el-select v-model="form.instanceId" placeholder="选择实例" style="width: 220px">
              <el-option
                v-for="inst in instances"
                :key="inst.id"
                :label="inst.label"
                :value="inst.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="方言">
            <el-select v-model="form.dialect" placeholder="选择方言" style="width: 160px">
              <el-option label="MySQL" value="MYSQL" />
              <el-option label="PostgreSQL" value="PG" />
            </el-select>
          </el-form-item>
        </div>

        <el-form-item label="SQL 语句">
          <el-input
            v-model="form.sql"
            type="textarea"
            :rows="6"
            class="sql-input"
            placeholder="SELECT * FROM orders WHERE ..."
          />
        </el-form-item>

        <div class="form-actions">
          <el-button
            type="primary"
            :loading="running"
            :disabled="!canRun"
            @click="run"
          >执行</el-button>
          <span class="run-hint">查询经 LIMIT 三层保护并写入审计链</span>
        </div>
      </el-form>
    </div>

    <!-- 失败 -->
    <el-alert
      v-if="result && result.success === false"
      type="error"
      :closable="false"
      show-icon
      class="result-alert"
      :title="result.reason || '查询失败'"
    />

    <!-- 成功 -->
    <div v-if="result && result.success" class="card result-card">
      <div class="info-bar">
        <div class="info-item">
          <span class="info-k">返回行数</span>
          <span class="info-v num">{{ result.rowCount }}</span>
        </div>
        <div class="info-item">
          <span class="info-k">耗时</span>
          <span class="info-v num">{{ result.elapsedMs }} ms</span>
        </div>
        <div class="info-item">
          <span class="info-k">LIMIT 保护</span>
          <el-tag :type="result.injected ? 'success' : 'info'" effect="light" size="small">
            {{ result.injected ? '已注入 LIMIT' : '未注入' }}
          </el-tag>
        </div>
        <div class="info-item">
          <span class="info-k">收紧</span>
          <el-tag :type="result.tightened ? 'warning' : 'info'" effect="light" size="small">
            {{ result.tightened ? '已收紧' : '未收紧' }}
          </el-tag>
        </div>
      </div>

      <div v-if="result.notice" class="notice">{{ result.notice }}</div>
      <div v-if="result.rewrittenSql" class="rewrite">
        <span class="rewrite-k">重写后 SQL</span>
        <pre class="mono rewrite-sql">{{ result.rewrittenSql }}</pre>
      </div>

      <el-alert
        v-if="result.truncated"
        type="warning"
        :closable="false"
        show-icon
        title="结果已截断，仅展示部分行"
      />

      <el-table :data="tableData" class="result-table" max-height="480">
        <el-table-column
          v-for="col in tableColumns"
          :key="col.prop"
          :prop="col.prop"
          :label="col.label"
          min-width="140"
        >
          <template #default="{ row }">
            <span :class="['mono', { 'cell-null': row[col.prop] == null }]">
              {{ row[col.prop] == null ? 'NULL' : String(row[col.prop]) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { api } from '../../api'
import { CURRENT_USER } from '../../constants'

// dev 假实例（W2 接实例元数据后替换）
const instances = [
  { id: 1, label: '生产 MySQL（id=1）' }
]

const form = reactive({
  instanceId: 1,
  dialect: 'MYSQL',
  sql: ''
})

const canRun = computed(() => form.sql.trim().length > 0 && form.instanceId != null && form.dialect)

const running = ref(false)
const result = ref(null)

async function run() {
  if (!canRun.value || running.value) return
  running.value = true
  try {
    result.value = await api.runQuery(form.instanceId, form.sql, form.dialect, CURRENT_USER.id)
  } catch {
    result.value = null
  } finally {
    running.value = false
  }
}

// 动态列：rows 为二维数组，转为对象便于 el-table 渲染（防列名重复用序号 key）
const tableColumns = computed(() => {
  if (!result.value?.success) return []
  return (result.value.columns || []).map((label, i) => ({ prop: `c${i}`, label }))
})

const tableData = computed(() => {
  if (!result.value?.success) return []
  return (result.value.rows || []).map((rowArr) => {
    const obj = {}
    ;(result.value.columns || []).forEach((_, i) => {
      obj[`c${i}`] = rowArr[i]
    })
    return obj
  })
})
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }

.card {
  background: var(--sa-surface);
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius);
  box-shadow: var(--sa-shadow);
  padding: 18px 20px 20px;
}
.card-head {
  padding-bottom: 14px;
  border-bottom: 1px solid var(--sa-border);
}
.card-title { font-size: 13px; font-weight: 650; color: var(--sa-text); }

.q-form { margin-top: 16px; }
.form-row { display: flex; gap: 24px; }

.sql-input :deep(textarea) {
  font-family: var(--sa-font-mono);
  font-size: 13px;
  line-height: 1.6;
}

.form-actions { display: flex; align-items: center; gap: 14px; }
.run-hint { font-size: 12px; color: var(--sa-text-3); }

.result-alert { border-radius: var(--sa-radius-sm); }

.result-card { display: flex; flex-direction: column; gap: 14px; }

.info-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 28px;
  padding: 12px 14px;
  background: var(--sa-surface-2);
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius-sm);
}
.info-item { display: flex; align-items: center; gap: 8px; }
.info-k { font-size: 11.5px; color: var(--sa-text-3); }
.info-v { font-size: 13px; font-weight: 600; color: var(--sa-text); }
.info-v.num { font-variant-numeric: tabular-nums; }

.notice {
  font-size: 12px;
  color: var(--sa-warning);
  background: var(--sa-warning-soft);
  border: 1px solid transparent;
  border-radius: var(--sa-radius-sm);
  padding: 8px 12px;
}

.rewrite {
  font-size: 12px;
  color: var(--sa-text-2);
}
.rewrite-k { display: block; font-size: 11.5px; color: var(--sa-text-3); margin-bottom: 4px; }
.rewrite-sql {
  font-family: var(--sa-font-mono);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--sa-surface-2);
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius-sm);
  padding: 10px 12px;
  margin: 0;
}

.result-table { margin-top: 2px; }

.mono { font-family: var(--sa-font-mono); font-size: 12px; }
.cell-null { color: var(--sa-text-3); font-style: italic; }
</style>
