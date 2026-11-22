<template>
  <div class="settings">
    <!-- 页头 -->
    <div class="page-head">
      <div>
        <div class="page-title">系统配置</div>
        <div class="page-desc">审核规则 · 审批流 · 实例接入（只读查询，配置变更需管理员，全程写审计）</div>
      </div>
      <span class="role-tag">当前角色：{{ roleLabel }} · 只读</span>
    </div>

    <!-- 配置页签 -->
    <el-card class="tabs-card" shadow="never">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <!-- 规则配置 -->
        <el-tab-pane label="规则配置" name="rules">
          <div class="pane-toolbar">
            <span class="toolbar-desc">审核规则引擎 · severity：ERROR 阻断 / WARN 提示</span>
          </div>

          <el-table v-loading="rulesLoading" :data="rules" class="pane-table" empty-text="暂无规则">
            <el-table-column label="规则 code" width="220">
              <template #default="{ row }">
                <span class="cell-mono mono">{{ row.code }}</span>
              </template>
            </el-table-column>
            <el-table-column label="方言" width="120">
              <template #default="{ row }"><span class="cell-dim">{{ row.dialect }}</span></template>
            </el-table-column>
            <el-table-column label="语句类型" width="140">
              <template #default="{ row }"><span class="cell-dim">{{ row.statementType }}</span></template>
            </el-table-column>
            <el-table-column label="级别" width="100">
              <template #default="{ row }">
                <el-tag effect="light" :type="severityType(row.severity)" size="small">
                  {{ row.severity }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="240">
              <template #default="{ row }"><span class="cell-dim">{{ row.description }}</span></template>
            </el-table-column>
            <el-table-column label="启用" width="90" align="center">
              <template #default="{ row }"><el-switch :model-value="!!row.enabled" disabled /></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 审批流配置 -->
        <el-tab-pane label="审批流配置" name="flows">
          <div class="pane-toolbar">
            <span class="toolbar-desc">flow_rule → flow_node · 全局默认 + 实例级覆盖 + 最严链兜底</span>
          </div>

          <el-table
            v-loading="flowsLoading"
            :data="flows"
            class="pane-table"
            empty-text="暂无审批流规则"
            @expand-change="onExpandChange"
          >
            <el-table-column type="expand">
              <template #default="{ row }">
                <div v-loading="!nodesMap[row.id]" class="nodes-panel">
                  <template v-if="nodesMap[row.id] && nodesMap[row.id].length">
                    <div v-for="(n, i) in nodesMap[row.id]" :key="n.id" class="node-row">
                      <span class="node-order">{{ i + 1 }}</span>
                      <span class="node-chip">{{ approverLabel(n.approverType) }}</span>
                      <span class="cell-mono mono">{{ n.approverRef }}</span>
                      <span class="node-mode">{{ n.mode === 'AND' ? '会签' : '或签' }}</span>
                      <span class="cell-dim">超时 {{ n.timeoutMinutes }} 分钟</span>
                    </div>
                  </template>
                  <el-empty
                    v-else-if="nodesMap[row.id]"
                    description="该流程暂无节点"
                    :image-size="48"
                  />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="名称" width="180">
              <template #default="{ row }"><span class="cell-row-title">{{ row.name }}</span></template>
            </el-table-column>
            <el-table-column label="环境" width="100">
              <template #default="{ row }">
                <el-tag effect="light" :type="envType(row.environment)" size="small">
                  {{ envLabel(row.environment) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="风险等级" width="110">
              <template #default="{ row }">
                <el-tag effect="light" :type="riskMeta(row.riskLevel).type" size="small">
                  {{ riskMeta(row.riskLevel).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="优先级" width="90" align="center">
              <template #default="{ row }"><span class="cell-dim">{{ row.priority }}</span></template>
            </el-table-column>
            <el-table-column label="覆盖策略" width="120">
              <template #default="{ row }">
                <el-tag effect="light" :type="row.nonOverridable ? 'danger' : 'info'" size="small">
                  {{ row.nonOverridable ? '不可覆盖' : '允许加严' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="90" align="center">
              <template #default="{ row }"><el-switch :model-value="!!row.enabled" disabled /></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 实例管理 -->
        <el-tab-pane label="实例管理" name="instances">
          <div class="pane-toolbar">
            <span class="toolbar-desc">生产库为核心资产：默认最小权限、按需授权、到期自动回收</span>
          </div>

          <el-table v-loading="instancesLoading" :data="instances" class="pane-table" empty-text="暂无实例">
            <el-table-column label="数据库" width="200">
              <template #default="{ row }"><span class="cell-row-title">{{ row.name }}</span></template>
            </el-table-column>
            <el-table-column label="连接" min-width="200">
              <template #default="{ row }">
                <span class="cell-mono mono">{{ row.host }}:{{ row.port }}</span>
              </template>
            </el-table-column>
            <el-table-column label="方言" width="110">
              <template #default="{ row }"><span class="cell-dim">{{ row.dialect }}</span></template>
            </el-table-column>
            <el-table-column label="环境" width="100">
              <template #default="{ row }">
                <el-tag effect="light" :type="envType(row.environment)" size="small">
                  {{ envLabel(row.environment) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag effect="light" :type="row.status === 'ACTIVE' ? 'success' : 'warning'" size="small">
                  {{ row.status === 'ACTIVE' ? '正常' : row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="Owner ID" width="110">
              <template #default="{ row }"><span class="cell-dim">{{ row.ownerId }}</span></template>
            </el-table-column>
            <el-table-column label="默认 Schema" min-width="160">
              <template #default="{ row }"><span class="cell-dim">{{ row.defaultSchema || '-' }}</span></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { api } from '../../api'
import { CURRENT_USER, riskMeta } from '../../constants'

const roleLabel =
  { DEV: '开发', LEAD: '主管', DBA: 'DBA', ADMIN: '管理员' }[CURRENT_USER.role] || CURRENT_USER.role

const activeTab = ref('rules')

// ---- 规则 ----
const rules = ref([])
const rulesLoading = ref(false)
let rulesLoaded = false

// ---- 审批流 ----
const flows = ref([])
const flowsLoading = ref(false)
let flowsLoaded = false
const nodesMap = reactive({})

// ---- 实例 ----
const instances = ref([])
const instancesLoading = ref(false)
let instancesLoaded = false

async function loadRules() {
  rulesLoading.value = true
  try {
    rules.value = await api.listRules()
    rulesLoaded = true
  } finally {
    rulesLoading.value = false
  }
}

async function loadFlows() {
  flowsLoading.value = true
  try {
    flows.value = await api.listFlows()
    flowsLoaded = true
  } finally {
    flowsLoading.value = false
  }
}

async function loadNodes(flow) {
  if (nodesMap[flow.id]) return
  const nodes = await api.listFlowNodes(flow.id)
  nodesMap[flow.id] = nodes
}

function onExpandChange(row, expandedRows) {
  if (expandedRows.some((r) => r.id === row.id)) loadNodes(row)
}

async function loadInstances() {
  instancesLoading.value = true
  try {
    instances.value = await api.listInstances()
    instancesLoaded = true
  } finally {
    instancesLoading.value = false
  }
}

function onTabChange(name) {
  if (name === 'rules' && !rulesLoaded) loadRules()
  else if (name === 'flows' && !flowsLoaded) loadFlows()
  else if (name === 'instances' && !instancesLoaded) loadInstances()
}

// 首次进入加载当前页签
onTabChange(activeTab.value)

function severityType(severity) {
  if (severity === 'ERROR') return 'danger'
  if (severity === 'WARN') return 'warning'
  return 'info'
}

function envType(env) {
  if (env === 'prod') return 'danger'
  if (env === 'staging') return 'warning'
  return 'info'
}

function envLabel(env) {
  return { prod: '生产', staging: '预发', ALL: '全部' }[env] || env || '-'
}

function approverLabel(type) {
  return { USER: '用户', ROLE: '角色', INSTANCE_OWNER: '实例 Owner' }[type] || type
}
</script>

<style scoped>
.settings { display: flex; flex-direction: column; gap: 16px; }

.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 18px; font-weight: 700; color: var(--sa-text); }
.page-desc { font-size: 12.5px; color: var(--sa-text-3); margin-top: 3px; }
.role-tag {
  font-size: 11px; color: var(--sa-text-2); background: var(--sa-surface-3);
  padding: 3px 10px; border-radius: 6px; font-weight: 600;
}

.tabs-card :deep(.el-card__body) { padding: 6px 18px 18px; }
.tabs-card :deep(.el-tabs__item) { font-weight: 600; }

.pane-toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin: 12px 0; }
.toolbar-desc { font-size: 12px; color: var(--sa-text-3); }

.cell-mono { font-family: var(--sa-font-mono); font-size: 12px; color: var(--sa-text); font-weight: 600; }
.cell-row-title { font-size: 13px; color: var(--sa-text); font-weight: 600; }
.cell-dim { color: var(--sa-text-2); font-size: 12px; }

.nodes-panel { padding: 8px 12px 8px 8px; min-height: 40px; }
.node-row {
  display: flex; align-items: center; gap: 10px; padding: 5px 0;
  border-bottom: 1px dashed var(--sa-surface-3);
}
.node-row:last-child { border-bottom: none; }
.node-order {
  width: 20px; height: 20px; line-height: 20px; text-align: center;
  border-radius: 50%; background: var(--sa-brand); color: #fff;
  font-size: 11px; font-weight: 700; flex-shrink: 0;
}
.node-chip {
  font-size: 11px; color: var(--sa-brand); background: var(--sa-brand-soft);
  padding: 2px 8px; border-radius: 5px; font-weight: 600;
}
.node-mode {
  font-size: 11px; color: var(--sa-text-2); background: var(--sa-surface-3);
  padding: 2px 8px; border-radius: 5px;
}
</style>
