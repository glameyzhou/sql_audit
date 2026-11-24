<template>
  <div class="page">
    <div class="card">
      <div class="card-head">
        <h3 class="card-title">线上查询</h3>
      </div>

      <el-form label-position="top" class="q-form">
        <!-- 实例选择 + 连接信息（方言随实例派生，不再独立选择） -->
        <div class="form-row">
          <el-form-item label="目标实例">
            <el-select
              v-model="instanceId"
              placeholder="选择实例"
              style="width: 260px"
              filterable
              :loading="instanceLoading"
            >
              <el-option
                v-for="inst in instanceOptions"
                :key="inst.id"
                :label="instanceLabel(inst)"
                :value="inst.id"
              >
                <span>{{ inst.name || '未命名实例' }}</span>
                <span class="option-meta">
                  <DbBadge v-if="inst.dialect" :dialect="inst.dialect" />
                  <span v-if="inst.defaultSchema">· 默认库 {{ inst.defaultSchema }}</span>
                </span>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="数据库">
            <div class="db-picker">
              <el-select
                v-model="selectedDb"
                placeholder="选择数据库"
                style="width: 200px"
                filterable
                :loading="dbLoading"
                :disabled="instanceId == null"
              >
                <el-option v-for="db in databases" :key="db" :label="db" :value="db" />
              </el-select>
              <el-button
                :icon="Refresh"
                link
                size="small"
                title="刷新库列表"
                :loading="dbLoading"
                :disabled="instanceId == null"
                @click="loadDatabases"
              />
            </div>
          </el-form-item>
          <el-form-item label="连接信息">
            <div v-if="currentInstance" class="inst-meta">
              <DbBadge v-if="currentInstance.dialect" :dialect="currentInstance.dialect" />
              <span v-else class="meta-text dim">?</span>
              <span class="meta-text mono">{{ hostPort(currentInstance) }}</span>
              <span class="meta-text">默认库 {{ currentInstance.defaultSchema || '-' }}</span>
            </div>
            <span v-else class="meta-text dim">
              {{ instanceLoading ? '实例加载中…' : (instanceList.length ? '未选择实例' : '无可用实例') }}
            </span>
          </el-form-item>
        </div>

        <!-- 工作台：左「数据表」+ 右「SQL 编辑 + 结果预览」；表列表可折叠成竖条给右侧让位 -->
        <div class="workbench" :class="{ 'table-collapsed': tableColCollapsed }">
          <div class="table-col">
            <!-- 折叠态：仅留竖条（展开按钮 + 竖排标题），横向空间全让给编辑器与结果 -->
            <div v-if="tableColCollapsed" class="table-rail">
              <el-button
                :icon="Expand"
                link
                size="small"
                title="展开表列表"
                @click="tableColCollapsed = false"
              />
              <span class="rail-text">数据表</span>
            </div>
            <template v-else>
              <div class="pane-head">
                <span>数据表</span>
                <span class="pane-actions">
                  <el-button
                    :icon="Refresh"
                    link
                    size="small"
                    title="刷新表列表"
                    :loading="tableLoading"
                    :disabled="!selectedDb"
                    @click="loadTables"
                  />
                  <el-button
                    :icon="Fold"
                    link
                    size="small"
                    title="折叠表列表"
                    @click="tableColCollapsed = true"
                  />
                </span>
              </div>
              <div v-loading="tableLoading" class="pane-body">
                <div v-if="instanceId == null" class="browser-state">先选择上方实例</div>
                <div v-else-if="dbError" class="browser-state err">库列表加载失败，点「数据库」旁刷新重试</div>
                <div v-else-if="!selectedDb" class="browser-state">先选择上方数据库</div>
                <div v-else-if="tableError" class="browser-state err">加载失败，点击刷新重试</div>
                <div v-else-if="!tables.length" class="browser-state">（空）</div>
                <div v-else class="browser-list">
                  <div
                    v-for="t in tables"
                    :key="t.name"
                    class="browser-item"
                    title="点击查看表结构，双击预览前 10 行"
                    @click="pickTable(t)"
                    @dblclick="previewTenRows(t)"
                  >
                    <div class="browser-item-main">
                      <span class="browser-name mono">{{ t.name }}</span>
                      <el-button
                        v-if="session.can(API.TABLE_REMARK_UPDATE)"
                        class="remark-edit"
                        link
                        size="small"
                        title="编辑备注"
                        @click.stop="openRemarkDialog(t)"
                      >备注</el-button>
                    </div>
                    <div v-if="remarkOf(t)" class="browser-remark">{{ remarkOf(t) }}</div>
                  </div>
                </div>
              </div>
            </template>
          </div>

          <div class="editor-col">
            <div class="pane-head">
              <span>SQL 工作台</span>
              <span class="pane-hint">支持 SELECT / EXPLAIN 看执行计划 / SHOW TABLES / DESC 表名（PG 同样适用）；EXPLAIN ANALYZE 会真实执行，已禁用</span>
            </div>

          <!-- textarea 透明文字 + 背后 <pre> 高亮层（同字体同 padding，滚动同步） -->
          <div ref="editorRef" class="sql-editor">
            <pre ref="hlRef" class="hl-layer" aria-hidden="true" v-html="highlightedSql"></pre>
            <textarea
              ref="taRef"
              v-model="form.sql"
              :rows="6"
              spellcheck="false"
              aria-label="SQL 语句"
              placeholder="SELECT * FROM orders WHERE ..."
              @input="onSqlInput"
              @click="onSqlClick"
              @keydown="onSqlKeydown"
              @scroll="syncScroll"
              @blur="closeSuggests"
            ></textarea>

            <!-- 自动提示面板（关键字 + 当前库表名） -->
            <div v-if="suggestVisible" class="suggest-panel" :style="suggestStyle">
              <div
                v-for="(s, i) in suggestList"
                :key="s.kind + s.text"
                class="suggest-item"
                :class="{ active: i === suggestIdx }"
                @mousedown.prevent="pickSuggest(s)"
                @mouseenter="suggestIdx = i"
              >
                <span class="mono">{{ s.text }}</span>
                <span class="suggest-kind">{{ s.kind }}</span>
              </div>
            </div>
          </div>

            <div class="form-actions">
              <el-button
                type="primary"
                :loading="running"
                :disabled="!canRun"
                @click="runFromButton"
              >执行</el-button>
              <span class="run-hint">查询经 LIMIT 三层保护并写入审计链</span>
            </div>

            <!-- 表结构面板（点左侧表名打开；查询通道只读，SHOW/DESC 不可用；可折叠只留标题行） -->
            <div v-if="structure" class="structure-panel" :class="{ collapsed: structureCollapsed }">
              <div class="result-head">
                <span class="result-title">表结构 · {{ structure.table }}</span>
                <div class="structure-actions">
                  <el-button
                    type="primary"
                    size="small"
                    :disabled="structure.loading || structure.error || !structure.columns.length"
                    @click="previewTenRows(structure.table)"
                  >查看前 10 行</el-button>
                  <el-button size="small" @click="structureCollapsed = !structureCollapsed">
                    {{ structureCollapsed ? '展开' : '折叠' }}
                  </el-button>
                  <el-button size="small" @click="structure = null">关闭</el-button>
                </div>
              </div>
              <div v-show="!structureCollapsed" v-loading="structure.loading" class="structure-body">
                <div v-if="structure.error" class="browser-state err">
                  表结构加载失败（可能是权限不足或表不存在）
                </div>
                <el-table
                  v-else
                  :data="structure.columns"
                  size="small"
                  class="structure-table"
                  max-height="320"
                >
                  <el-table-column prop="ordinal" label="#" width="52" />
                  <el-table-column prop="name" label="字段" min-width="150">
                    <template #default="{ row }"><span class="mono">{{ row.name }}</span></template>
                  </el-table-column>
                  <el-table-column prop="dataType" label="类型" min-width="130">
                    <template #default="{ row }"><span class="mono">{{ row.dataType }}</span></template>
                  </el-table-column>
                  <el-table-column label="可空" width="70">
                    <template #default="{ row }">{{ row.nullable ? '是' : '否' }}</template>
                  </el-table-column>
                  <el-table-column label="键" width="90">
                    <template #default="{ row }">
                      <el-tag v-if="keyLabel(row.key)" size="small" effect="light" type="info">
                        {{ keyLabel(row.key) }}
                      </el-tag>
                      <span v-else class="meta-text dim">—</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="默认值" min-width="120">
                    <template #default="{ row }">
                      <span class="mono">{{ row.defaultValue ?? '—' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="注释" min-width="170" show-overflow-tooltip>
                    <template #default="{ row }">{{ row.comment || '—' }}</template>
                  </el-table-column>
                </el-table>
              </div>
            </div>

            <!-- 表备注编辑弹窗 -->
            <el-dialog
              v-model="remarkDialog.visible"
              :title="'表备注 · ' + remarkDialog.table"
              width="420px"
              append-to-body
            >
              <el-input
                v-model="remarkDialog.remark"
                type="textarea"
                :rows="3"
                maxlength="255"
                show-word-limit
                placeholder="平台侧备注（优先于原库注释显示）；留空保存 = 清除备注"
              />
              <template #footer>
                <el-button @click="remarkDialog.visible = false">取消</el-button>
                <el-button type="primary" :loading="remarkDialog.saving" @click="saveRemark">保存</el-button>
              </template>
            </el-dialog>

            <!-- 失败 -->
            <el-alert
              v-if="result && result.success === false"
              type="error"
              :closable="false"
              show-icon
              class="result-alert"
              :title="result.reason || '查询失败'"
            />

            <!-- 成功：结果紧贴编辑器下方展示（点表预览与手动查询共用） -->
            <div v-if="result && result.success" class="result-inline">
              <div class="result-head">
                <span class="result-title">{{ previewMode ? '数据预览（前 10 行）' : '查询结果' }}</span>
              </div>
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
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { Refresh, Fold, Expand } from '@element-plus/icons-vue'
import { api } from '../../api'
import { API } from '../../api/capabilities'
import { useSessionStore } from '../../stores/session'
import DbBadge from '../../components/DbBadge.vue'

// 能力判断（菜单 / 按钮显隐走接口能力，不认角色码）
const session = useSessionStore()

// ==================== 实例选择 ====================

// 后端已按当前用户角色的数据范围过滤，前端不再做二次筛选
const instanceList = ref([])
const instanceLoading = ref(false)
const instanceId = ref(null)

const instanceOptions = computed(() => instanceList.value || [])

const currentInstance = computed(() =>
  (instanceList.value || []).find((i) => i.id === instanceId.value) || null
)
// 方言随实例派生（旧 form.dialect 独立选择已移除）
const currentDialect = computed(() => currentInstance.value?.dialect || '')

function instanceLabel(inst) {
  const schema = inst.defaultSchema ? ` · 默认库 ${inst.defaultSchema}` : ''
  return `${inst.name || '未命名实例'} · ${inst.dialect || '?'}${schema}`
}

function hostPort(inst) {
  const h = inst.host || ''
  const p = inst.port != null ? String(inst.port) : ''
  if (h && p) return `${h}:${p}`
  return h || p || '-'
}

async function loadInstances() {
  instanceLoading.value = true
  try {
    instanceList.value = (await api.listUserInstances()) || []
    // 默认选中第一个实例，省去一次点击
    if (instanceList.value.length && instanceId.value == null) {
      instanceId.value = instanceList.value[0].id
    }
  } catch {
    /* 拦截器已提示 */
    instanceList.value = []
  } finally {
    instanceLoading.value = false
  }
}

// ==================== 库 / 表两列浏览 ====================

const databases = ref([])
// 表列表元素 = { name, comment }（comment 为原库原生注释，可为 null）
const tables = ref([])
// 左侧表列表折叠开关（折叠后仅留竖条，编辑器/结果区占满整行）
const tableColCollapsed = ref(false)
const selectedDb = ref('')
const dbLoading = ref(false)
const dbError = ref(false)
const tableLoading = ref(false)
const tableError = ref(false)

// 序号守卫：实例/库快速切换时丢弃过期响应
let dbSeq = 0
let tableSeq = 0

// 列名缓存：表标识符 → 列名数组（随库/表切换失效）
const columnCache = new Map()

// 平台备注词典：表名 → 备注（平台备注优先于原生注释展示）
const remarks = ref(new Map())

async function loadDatabases() {
  const id = instanceId.value
  if (id == null) return
  const seq = ++dbSeq
  dbLoading.value = true
  dbError.value = false
  selectedDb.value = ''
  tables.value = []
  remarks.value = new Map()
  structure.value = null
  columnCache.clear()
  tableError.value = false
  try {
    const list = await api.listDatabases(id)
    if (seq !== dbSeq) return
    // 实例配置了默认库 → 下拉收敛为仅默认库（「实例=默认库」语义，跨库已由后端边界拒绝）；
    // 未配置默认库 → 保留全量库列表（无锚点，用户可自由选库，边界同样不限制）。
    const preferred = currentInstance.value?.defaultSchema
    databases.value = preferred && list?.includes(preferred) ? [preferred] : (list || [])
    // 默认选中默认库（存在时），否则取第一个，省去一次点击
    selectedDb.value = preferred && databases.value.includes(preferred)
      ? preferred
      : (databases.value[0] || '')
  } catch {
    if (seq !== dbSeq) return
    dbError.value = true
    databases.value = []
  } finally {
    if (seq === dbSeq) dbLoading.value = false
  }
}

async function loadTables() {
  const id = instanceId.value
  const db = selectedDb.value
  if (id == null || !db) return
  const seq = ++tableSeq
  tableLoading.value = true
  tableError.value = false
  remarks.value = new Map()
  structure.value = null
  try {
    const list = await api.listTables(id, db)
    if (seq !== tableSeq) return
    tables.value = list || []
    columnCache.clear()
    await loadRemarks(seq)
  } catch {
    if (seq !== tableSeq) return
    tableError.value = true
    tables.value = []
  } finally {
    if (seq === tableSeq) tableLoading.value = false
  }
}

/** 拉平台备注词典（失败静默置空——原生注释仍在表列表里展示）。 */
async function loadRemarks(seq = tableSeq) {
  const id = instanceId.value
  const db = selectedDb.value
  if (id == null || !db) {
    remarks.value = new Map()
    return
  }
  let list
  try {
    list = await api.listTableRemarks(id, db)
  } catch {
    list = []
  }
  if (seq !== tableSeq) return
  const m = new Map()
  for (const r of list || []) {
    if (r && r.tableName != null) m.set(r.tableName, r.remark || '')
  }
  remarks.value = m
}

/** 展示备注：平台备注优先，回落原生注释，都没有则不显示。 */
function remarkOf(t) {
  const name = t?.name ?? String(t)
  const platform = remarks.value.get(name)
  if (platform) return platform
  return t?.comment || ''
}

// 数据库切换（下拉）→ 重新拉表清单；空值（清空 / 切实例）时清空表列表
watch(selectedDb, (db) => {
  tables.value = []
  remarks.value = new Map()
  structure.value = null
  tableError.value = false
  closeSuggests()
  if (db) loadTables()
})

// 标识符引用符：MySQL 反引号 / PG 双引号 / IoTDB 不引用（时序路径 root.sg.d1 / 表名）
function quoteIdent(name) {
  const d = currentDialect.value.toUpperCase()
  if (d === 'PG') return `"${name}"`
  if (d === 'IOTDB') return name
  return `\`${name}\``
}

// ==================== 表结构面板 + 表备注 ====================

// 结构面板状态：{ table, loading, error, columns }；null = 未打开
const structure = ref(null)
// 表结构面板折叠（收起字段表只留标题行，给结果区让位）
const structureCollapsed = ref(false)

// 点表（单击）→ 打开表结构面板（不再自动跑 SELECT）
async function pickTable(t) {
  if (!selectedDb.value) return
  const name = t?.name ?? String(t)
  structureCollapsed.value = false
  structure.value = { table: name, loading: true, error: false, columns: [] }
  try {
    const cols = await api.listTableStructure(instanceId.value, selectedDb.value, name)
    if (structure.value?.table !== name) return
    structure.value = { table: name, loading: false, error: false, columns: cols || [] }
  } catch {
    if (structure.value?.table !== name) return
    structure.value = { table: name, loading: false, error: true, columns: [] }
  }
}

/** 生成 SELECT ... LIMIT 10（PG 的 schema.table 限定名拆开引用）。 */
function buildPreviewSelect(name) {
  const d = currentDialect.value.toUpperCase()
  if (d === 'PG') {
    // PG：后端 listTables 返回 schema.table 限定名；数据库只是连接上下文不写进 SQL，
    // 拆成 "schema"."table" 分别引用，避免 "库"."schema.table" 这类非法限定
    const dot = name.indexOf('.')
    return dot > 0
      ? `SELECT * FROM ${quoteIdent(name.slice(0, dot))}.${quoteIdent(name.slice(dot + 1))} LIMIT 10`
      : `SELECT * FROM ${quoteIdent(name)} LIMIT 10`
  }
  if (d === 'IOTDB') {
    // 表模型：SELECT * FROM db.table；树模型：name 已是设备全路径 root.sg.d1（不加库前缀）
    const tableModel = currentInstance.value?.iotdbSqlDialect === 'TABLE'
    return tableModel
      ? `SELECT * FROM ${selectedDb.value}.${name} LIMIT 10`
      : `SELECT * FROM ${name} LIMIT 10`
  }
  return `SELECT * FROM ${quoteIdent(selectedDb.value)}.${quoteIdent(name)} LIMIT 10`
}

// 预览前 10 行（结构面板按钮 / 表列表双击）
function previewTenRows(t) {
  if (!selectedDb.value) return
  const name = t?.name ?? String(t)
  form.sql = buildPreviewSelect(name)
  nextTick(() => taRef.value?.focus())
  run(true)
}

// 表备注弹窗
const remarkDialog = reactive({ visible: false, table: '', remark: '', saving: false })

function openRemarkDialog(t) {
  const name = t?.name ?? String(t)
  remarkDialog.table = name
  remarkDialog.remark = remarks.value.get(name) || ''
  remarkDialog.visible = true
}

async function saveRemark() {
  remarkDialog.saving = true
  try {
    await api.saveTableRemark({
      instanceId: instanceId.value,
      schemaName: selectedDb.value,
      tableName: remarkDialog.table,
      remark: remarkDialog.remark.trim()
    })
    remarkDialog.visible = false
    await loadRemarks()
  } catch {
    /* 拦截器已提示 */
  } finally {
    remarkDialog.saving = false
  }
}

// 键代码 → 中文标签（MySQL：PRI/UNI/MUL；PG：PRI/UNI）
function keyLabel(key) {
  return { PRI: '主键', UNI: '唯一', MUL: '索引' }[key] || ''
}

// 实例切换：重置库表并加载新实例的库
watch(instanceId, () => {
  closeSuggests()
  if (instanceId.value != null) {
    loadDatabases()
  } else {
    selectedDb.value = ''
    databases.value = []
    tables.value = []
    remarks.value = new Map()
    structure.value = null
  }
})

// ==================== 查询执行 ====================

const form = reactive({ sql: '' })

const canRun = computed(() =>
  form.sql.trim().length > 0 && instanceId.value != null && !!currentDialect.value
)

const running = ref(false)
const result = ref(null)
/** 结果区标题口径：点表触发的 20 行预览 vs 手动执行 */
const previewMode = ref(false)

function runFromButton() {
  previewMode.value = false
  run()
}

async function run(preview = false) {
  if (!canRun.value || running.value) return
  previewMode.value = preview
  running.value = true
  try {
    result.value = await api.runQuery(instanceId.value, form.sql, currentDialect.value)
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

// ==================== SQL 语法高亮（零依赖 overlay） ====================

// 关键字列表（同时作为自动提示候选）
const SQL_KEYWORDS = [
  'SELECT', 'FROM', 'WHERE', 'INSERT', 'UPDATE', 'DELETE', 'LIMIT', 'ORDER', 'BY', 'GROUP', 'HAVING',
  'JOIN', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'FULL', 'CROSS', 'ON', 'AND', 'OR', 'NOT', 'NULL', 'AS',
  'IN', 'LIKE', 'IS', 'BETWEEN', 'EXISTS', 'UNION', 'ALL', 'DISTINCT', 'COUNT', 'SUM', 'AVG', 'MIN',
  'MAX', 'CREATE', 'ALTER', 'DROP', 'TABLE', 'INDEX', 'SET', 'VALUES', 'INTO', 'PRIMARY', 'KEY',
  'FOREIGN', 'REFERENCES', 'DESC', 'ASC', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'SHOW', 'USE',
  'TRUNCATE', 'ADD', 'COLUMN', 'DEFAULT', 'IF', 'BEGIN', 'COMMIT', 'ROLLBACK', 'WITH', 'OFFSET', 'FETCH',
  // 查询控制台实用语句（后端翻译/放行）：EXPLAIN 执行计划、SHOW TABLES、DESC 表结构
  'EXPLAIN', 'ANALYZE', 'TABLES', 'DATABASES', 'DESCRIBE'
]
const KEYWORD_SET = new Set(SQL_KEYWORDS)

// 单遍分词（按优先级）：注释 / 字符串 / 反引号标识符 / 数字 / 单词。
// 避免「先正则后正则」的嵌套 span 问题；每段先转义 HTML 再包 span，杜绝 XSS。
// /* */ 注释支持跨行（[\s\S]*?）。
const SQL_TOKEN_RE = /(--[^\n]*|#[^\n]*|\/\*[\s\S]*?\*\/|'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"|`(?:[^`\\]|\\.)*`|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b)/g

function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function highlightSql(sql) {
  if (!sql) return ''
  let out = ''
  let last = 0
  SQL_TOKEN_RE.lastIndex = 0
  let m
  while ((m = SQL_TOKEN_RE.exec(sql))) {
    out += escapeHtml(sql.slice(last, m.index))
    const tok = m[0]
    if (tok[0] === '-' || tok[0] === '#' || tok[0] === '/') {
      out += `<span class="tk-c">${escapeHtml(tok)}</span>` // 注释
    } else if (tok[0] === "'" || tok[0] === '"') {
      out += `<span class="tk-s">${escapeHtml(tok)}</span>` // 字符串
    } else if (tok[0] === '`') {
      out += `<span class="tk-i">${escapeHtml(tok)}</span>` // 反引号标识符
    } else if (/^\d/.test(tok)) {
      out += `<span class="tk-n">${escapeHtml(tok)}</span>` // 数字
    } else if (KEYWORD_SET.has(tok.toUpperCase())) {
      out += `<span class="tk-k">${escapeHtml(tok)}</span>` // 关键字
    } else {
      out += escapeHtml(tok)
    }
    last = m.index + tok.length
  }
  out += escapeHtml(sql.slice(last))
  return out
}

const highlightedSql = computed(() => highlightSql(form.sql))

// ==================== 自动提示 ====================

const editorRef = ref(null)
const hlRef = ref(null)
const taRef = ref(null)

const suggestVisible = ref(false)
const suggestList = ref([])
const suggestIdx = ref(0)
const suggestStyle = ref({})
const PANEL_WIDTH = 260
let suggestSeq = 0 // 异步列加载的序号守卫（丢弃过期响应）

// 光标前一个「词」（[A-Za-z0-9_] 连续段）的起点与文本
function wordPrefixAtCaret(value, caret) {
  let i = caret
  while (i > 0 && /[A-Za-z0-9_]/.test(value[i - 1])) i--
  return { start: i, text: value.slice(i, caret) }
}

// 候选 = 关键字（前缀匹配，忽略大小写） + 当前库已加载的表名，最多 8 条
function buildSuggestions(prefix) {
  if (!prefix) return []
  const up = prefix.toUpperCase()
  const low = prefix.toLowerCase()
  const out = []
  for (const k of SQL_KEYWORDS) {
    if (k.startsWith(up)) out.push({ text: k, kind: '关键字', insert: k + ' ' })
  }
  for (const t of tables.value || []) {
    const name = String(t?.name ?? t)
    if (name.toLowerCase().startsWith(low)) out.push({ text: name, kind: '表', insert: name })
  }
  return out.slice(0, 8)
}

// 检测光标是否处于「别名/表名.列前缀」上下文（如 t. 或 t.na）；返回 { qualifier, colPrefix } 或 null。
// 仅匹配未加引号、未加空格的「词.词」形态，最后一个 . 之前是限定名、之后是列前缀。
function dotContextAtCaret(value, caret) {
  const m = value.slice(0, caret).match(/([A-Za-z0-9_]+)\.([A-Za-z0-9_]*)$/)
  if (!m) return null
  return { qualifier: m[1], colPrefix: m[2] }
}

// 解析 FROM/JOIN 子句，建立「别名 / 裸表名 → 表标识符（tables.value 里的确切字符串）」映射。
// 覆盖逗号分隔多表与 [AS] 别名；子查询 / CTE 不做展开（遇括号保守放弃该段）。
function buildTableAliasMap(sql) {
  const bareToFull = new Map()
  for (const t of tables.value || []) {
    const full = String(t?.name ?? t)
    const bare = full.split('.').pop().replace(/[`"]/g, '')
    if (bare && !bareToFull.has(bare)) bareToFull.set(bare, full)
  }
  // 去引号 + 去尾部点号（尾部点号是「t.」正在输入的中间态）
  const clean = (s) => s.replace(/[`"]/g, '').replace(/\.+$/, '')
  const resolve = (ref) => {
    const stripped = clean(ref)
    return bareToFull.get(stripped.split('.').pop()) || stripped
  }
  const map = new Map()
  const JOIN_KW = new Set(['JOIN', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'FULL', 'CROSS'])
  const CLAUSE_END = new Set(['WHERE', 'GROUP', 'ORDER', 'HAVING', 'LIMIT', 'OFFSET', 'UNION',
    'SELECT', 'ON', 'SET', 'VALUES', 'RETURNING', 'INTO'])
  const tokens = sql.match(/[`"]?[A-Za-z_][A-Za-z0-9_.]*[`"]?|[,()]/g) || []
  let inFrom = false
  for (let i = 0; i < tokens.length; i++) {
    const tok = tokens[i]
    const up = tok.toUpperCase()
    if (up === 'FROM' || JOIN_KW.has(up)) { inFrom = true; continue }
    if (!inFrom) continue
    if (CLAUSE_END.has(up)) { inFrom = false; continue }
    if (tok === '(' || tok === ')') { inFrom = false; continue } // 子查询：放弃该段
    if (tok === ',') continue
    // 表引用：其后可选 [AS] 别名
    let alias = null
    let j = i + 1
    if (tokens[j] && tokens[j].toUpperCase() === 'AS') j++
    const next = tokens[j]
    if (next && next !== ',' && next !== '(' && next !== ')'
        && !CLAUSE_END.has(next.toUpperCase()) && !JOIN_KW.has(next.toUpperCase())) {
      alias = clean(next).split('.')[0] // 别名是单标识符，. 之后是列引用，丢弃
      i = j
    }
    const full = resolve(tok)
    const key = alias || clean(tok).split('.').pop()
    if (!map.has(key)) map.set(key, full)
  }
  return map
}

// 按表标识符取列名（带缓存 + 序号守卫）；请求失败返回空数组并缓存，避免反复弹错
async function loadColumns(fullTable, seq) {
  if (columnCache.has(fullTable)) return columnCache.get(fullTable)
  let list
  try {
    list = await api.listColumns(instanceId.value, selectedDb.value, fullTable)
  } catch {
    list = []
  }
  if (seq !== suggestSeq) return null
  columnCache.set(fullTable, list || [])
  return columnCache.get(fullTable)
}

// 展示提示面板（统一入口：填充列表 + 定位）
function showSuggest(list) {
  suggestList.value = list
  suggestIdx.value = 0
  suggestVisible.value = true
  updateSuggestPosition()
}

// —— 光标像素位置：mirror div 复刻 textarea 的盒模型/字体（标准 textarea-caret 技法） ——
let caretMirror = null
const MIRROR_BASE =
  'position:absolute;top:0;left:0;visibility:hidden;pointer-events:none;' +
  'white-space:pre-wrap;word-wrap:break-word;overflow-wrap:break-word;'
const MIRROR_PROPS = [
  'boxSizing', 'width', 'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth',
  'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
  'fontStyle', 'fontVariant', 'fontWeight', 'fontFamily', 'fontSize', 'lineHeight',
  'letterSpacing', 'wordSpacing', 'textTransform', 'textIndent', 'tabSize'
]

function caretPixelPos(ta) {
  if (!caretMirror) {
    caretMirror = document.createElement('div')
    document.body.appendChild(caretMirror)
  }
  const style = window.getComputedStyle(ta)
  const css = MIRROR_PROPS.map((p) => `${p}:${style[p]}`).join(';')
  caretMirror.style.cssText = MIRROR_BASE + ';' + css
  caretMirror.textContent = ta.value.substring(0, ta.selectionStart)
  const span = document.createElement('span')
  span.textContent = ta.value.substring(ta.selectionStart) || '.'
  caretMirror.appendChild(span)
  const coords = { top: span.offsetTop - ta.scrollTop, left: span.offsetLeft - ta.scrollLeft }
  caretMirror.removeChild(span)
  return coords
}

// 面板定位：始终显示在光标所在行的下一行，避免覆盖正在输入的字符；水平方向夹在编辑器内。
// 面板可溢出编辑器底部（.sql-editor 无 overflow 裁剪），像常规下拉一样盖在结果区上方。
function updateSuggestPosition() {
  const ta = taRef.value
  const wrap = editorRef.value
  if (!ta || !wrap) return
  const coords = caretPixelPos(ta)
  const lineH = parseFloat(window.getComputedStyle(ta).lineHeight) || 20
  const left = Math.min(Math.max(ta.clientLeft + coords.left, 4), Math.max(wrap.clientWidth - PANEL_WIDTH - 4, 4))
  const top = ta.clientTop + coords.top + lineH + 4
  suggestStyle.value = { left: left + 'px', top: top + 'px', width: PANEL_WIDTH + 'px' }
}

async function refreshSuggest() {
  const ta = taRef.value
  if (!ta) return
  const seq = ++suggestSeq
  const value = ta.value
  const caret = ta.selectionStart

  // 1) 「别名/表名.列」上下文 → 列补全
  const dot = dotContextAtCaret(value, caret)
  if (dot) {
    const full = buildTableAliasMap(value).get(dot.qualifier)
    if (!full) { closeSuggests(); return }
    const cols = await loadColumns(full, seq)
    if (seq !== suggestSeq) return
    const prefix = dot.colPrefix.toLowerCase()
    const list = (cols || [])
      .filter((c) => String(c).toLowerCase().startsWith(prefix))
      .map((c) => ({ text: String(c), kind: '列', insert: String(c) }))
      .slice(0, 50)
    if (list.length) showSuggest(list)
    else closeSuggests()
    return
  }

  // 2) 关键字 / 表名
  const { text } = wordPrefixAtCaret(value, caret)
  const list = buildSuggestions(text)
  if (list.length) showSuggest(list)
  else closeSuggests()
}

function closeSuggests() {
  suggestVisible.value = false
  suggestList.value = []
  suggestIdx.value = 0
}

function onSqlInput() {
  refreshSuggest()
  syncScroll()
}

function onSqlClick() {
  refreshSuggest()
}

function onSqlKeydown(e) {
  if (suggestVisible.value && suggestList.value.length) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      suggestIdx.value = (suggestIdx.value + 1) % suggestList.value.length
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      suggestIdx.value = (suggestIdx.value - 1 + suggestList.value.length) % suggestList.value.length
      return
    }
    if (e.key === 'Enter' || e.key === 'Tab') {
      e.preventDefault()
      pickSuggest(suggestList.value[suggestIdx.value])
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      closeSuggests()
      return
    }
    // 水平移动光标后面板位置会失准，直接收起
    if (e.key === 'ArrowLeft' || e.key === 'ArrowRight' || e.key === 'Home' || e.key === 'End') {
      closeSuggests()
      return
    }
  } else if (e.key === 'Tab') {
    // 面板关闭时 Tab 插入两个空格缩进，避免焦点跳走
    e.preventDefault()
    insertText('  ')
  }
}

// 在光标处插入文本（保留选区替换语义），并把光标放到插入内容之后
function insertText(text) {
  const ta = taRef.value
  if (!ta) return
  const start = ta.selectionStart
  const end = ta.selectionEnd
  form.sql = form.sql.slice(0, start) + text + form.sql.slice(end)
  nextTick(() => {
    ta.setSelectionRange(start + text.length, start + text.length)
    syncScroll()
  })
}

// 选中候选：替换光标前的词为候选内容，光标落在插入内容之后
function pickSuggest(s) {
  const ta = taRef.value
  if (!ta) return
  const { start } = wordPrefixAtCaret(form.sql, ta.selectionStart)
  form.sql = form.sql.slice(0, start) + s.insert + form.sql.slice(ta.selectionStart)
  const caret = start + s.insert.length
  nextTick(() => {
    ta.focus()
    ta.setSelectionRange(caret, caret)
    syncScroll()
  })
  closeSuggests()
}

// 高亮层与 textarea 滚动同步 + 宽度对齐（不含滚动条，保证软换行一致）
function syncScroll() {
  const ta = taRef.value
  const hl = hlRef.value
  if (!ta || !hl) return
  hl.scrollTop = ta.scrollTop
  hl.scrollLeft = ta.scrollLeft
  hl.style.width = ta.clientWidth + 'px'
}

let resizeObserver = null
onMounted(() => {
  loadInstances()
  nextTick(() => {
    syncScroll()
    // textarea 宽度/高度变化（含拖拽 resize）时重新对齐高亮层
    if (window.ResizeObserver && taRef.value) {
      resizeObserver = new ResizeObserver(() => syncScroll())
      resizeObserver.observe(taRef.value)
    }
  })
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
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

/* 实例下拉选项：名称在左、方言徽章与默认库在右（与提交变更页一致） */
.option-meta { float: right; font-size: 11px; color: var(--sa-text-3); margin-left: 12px; display: inline-flex; align-items: center; gap: 6px; }

.q-form { margin-top: 16px; }
.form-row { display: flex; align-items: flex-start; gap: 20px; flex-wrap: wrap; }

.inst-meta { display: flex; align-items: center; gap: 10px; min-height: 32px; }
.meta-text { font-size: 12px; color: var(--sa-text-2); }
.meta-text.dim { color: var(--sa-text-3); }

/* ---- 工作台：左表列表 + 右编辑器/结果 ---- */
.workbench {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 18px;
  /* stretch：表列表跟随右侧编辑器/结果区的高度撑满（右侧更高时列表也跟着变高） */
  align-items: stretch;
}
/* 表列表折叠态：左列缩成竖条宽度 */
.workbench.table-collapsed { grid-template-columns: 34px minmax(0, 1fr); gap: 10px; }
.table-col { min-width: 0; display: flex; flex-direction: column; }
.editor-col { min-width: 0; display: flex; flex-direction: column; gap: 12px; }
.pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--sa-text-3);
  margin-bottom: 6px;
  padding: 0 2px;
  min-height: 24px;
  flex-shrink: 0;
}
.pane-hint { font-size: 11.5px; font-weight: 400; color: var(--sa-text-3); }
/* pane-head 右侧按钮组（刷新 + 折叠） */
.pane-actions { display: inline-flex; align-items: center; gap: 2px; flex-shrink: 0; }
.pane-body {
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius-sm);
  background: var(--sa-surface-2);
  min-height: 260px;
  flex: 1 1 auto;
  overflow: auto;
  padding: 4px;
}
/* 折叠后的表列表竖条：展开按钮 + 竖排标题 */
.table-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius-sm);
  background: var(--sa-surface-2);
  padding: 10px 0;
}
.rail-text {
  writing-mode: vertical-rl;
  font-size: 12px;
  font-weight: 600;
  color: var(--sa-text-3);
  letter-spacing: 6px;
  user-select: none;
}
.db-picker { display: flex; align-items: center; gap: 6px; }
.browser-list { display: flex; flex-direction: column; gap: 1px; }
.browser-item {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 4px 10px;
  font-size: 12.5px;
  color: var(--sa-text-2);
  border-radius: 5px;
  cursor: pointer;
}
.browser-item:hover { background: var(--sa-surface-3); color: var(--sa-text); }
.browser-item.active { background: var(--sa-brand-soft); color: var(--sa-brand-2); font-weight: 600; }
.browser-item-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  min-width: 0;
}
.browser-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.browser-remark {
  font-size: 11px;
  color: var(--sa-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.browser-item:hover .browser-remark { color: var(--sa-text-2); }
/* 备注编辑按钮：悬停才出现，避免列表常态下过挤 */
.remark-edit { flex-shrink: 0; visibility: hidden; font-size: 11px; }
.browser-item:hover .remark-edit { visibility: visible; }
.browser-state { padding: 14px 8px; text-align: center; font-size: 12px; color: var(--sa-text-3); }
.browser-state.err { color: var(--sa-danger); }

/* ---- 表结构面板 ---- */
.structure-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius-sm);
  background: var(--sa-surface);
  padding: 12px 14px;
}
.structure-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
/* 折叠态：只留标题行，收紧上下内边距 */
.structure-panel.collapsed { padding: 8px 14px; }
.structure-body { min-height: 48px; }
.structure-table { margin-top: 2px; }

/* ---- SQL 编辑器（textarea + 高亮 overlay） ---- */
.sql-editor {
  position: relative;
  border: 1px solid var(--sa-border-2);
  border-radius: var(--sa-radius-sm);
  background: var(--sa-surface);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.sql-editor:focus-within {
  border-color: var(--sa-brand-2);
  box-shadow: 0 0 0 1px var(--sa-brand-2) inset;
}

/* 文字透明、只看插入符；高亮由下方 .hl-layer 呈现 */
.sql-editor textarea {
  position: relative;
  z-index: 1;
  display: block;
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  color: transparent;
  -webkit-text-fill-color: transparent;
  caret-color: var(--sa-text);
  font-family: var(--sa-font-mono);
  font-size: 13px;
  line-height: 1.6;
  padding: 8px 12px;
  resize: vertical;
  overflow: auto;
  tab-size: 4;
}
.sql-editor textarea::placeholder {
  color: var(--sa-text-3);
  -webkit-text-fill-color: var(--sa-text-3);
}
/* textarea 文字透明、位于 .hl-layer 之上；选区背景必须半透明，
   否则不透明选区会整块盖住下方的高亮文字，导致选中后看不清内容 */
.sql-editor textarea::selection { background: rgba(15, 168, 183, 0.2); }

/* 高亮层：与 textarea 同字体/字号/行高/padding，overflow 隐藏靠滚动同步 */
.hl-layer {
  position: absolute;
  inset: 0;
  z-index: 0;
  margin: 0;
  padding: 8px 12px;
  overflow: hidden;
  pointer-events: none;
  font-family: var(--sa-font-mono);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-wrap: break-word;
  overflow-wrap: break-word;
  tab-size: 4;
  color: var(--sa-text);
}
/* v-html 注入的 token span 不带 scoped 属性，需 :deep 命中 */
.hl-layer :deep(.tk-k) { color: var(--sa-info); font-weight: 600; }
.hl-layer :deep(.tk-s) { color: var(--sa-success); }
.hl-layer :deep(.tk-n) { color: var(--sa-purple); }
.hl-layer :deep(.tk-c) { color: var(--sa-text-3); font-style: italic; }
.hl-layer :deep(.tk-i) { color: var(--sa-warning); }

/* ---- 自动提示面板 ---- */
.suggest-panel {
  position: absolute;
  z-index: 10;
  background: var(--sa-surface);
  border: 1px solid var(--sa-border-2);
  border-radius: var(--sa-radius-sm);
  box-shadow: var(--sa-shadow-lg);
  padding: 4px 0;
  max-height: 240px;
  overflow: auto;
}
.suggest-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 10px;
  font-size: 12.5px;
  cursor: pointer;
}
.suggest-item:hover, .suggest-item.active { background: var(--sa-surface-3); }
.suggest-item.active { color: var(--sa-brand-2); }
.suggest-kind { font-size: 11px; color: var(--sa-text-3); flex-shrink: 0; }

.form-actions { display: flex; align-items: center; gap: 14px; }
.run-hint { font-size: 12px; color: var(--sa-text-3); }

.result-alert { border-radius: var(--sa-radius-sm); }

/* 结果紧贴编辑器下方（不再单独一张大卡片） */
.result-inline {
  display: flex;
  flex-direction: column;
  gap: 12px;
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius-sm);
  background: var(--sa-surface);
  padding: 12px 14px;
}
.result-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}
.result-title { font-size: 12.5px; font-weight: 650; color: var(--sa-text); }

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
