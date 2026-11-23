<template>
  <div class="config-page">
    <div class="page-head">
      <div>
        <div class="page-title">审批流配置</div>
        <div class="page-desc">生效链 = 全局链 + 该实例专用链（实例节点追加在后，只会更严不会更松）</div>
      </div>
      <span class="role-tag">当前角色：{{ session.roleLabel }} · {{ canEdit ? '可编辑' : '只读' }}</span>
    </div>

    <el-card class="pane-card" shadow="never">
      <div class="pane-toolbar">
        <span class="toolbar-desc">
          每个「范围 × 风险等级」至多一条流程；非 LOW 等级生效链为空时将拒绝提交工单
        </span>
        <el-button
          v-if="session.can(API.FLOW_CREATE)"
          type="primary"
          size="small"
          class="toolbar-action"
          @click="openCreateDialog()"
        >新增流程</el-button>
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
              <template v-if="nodeGroupsOf(row.id).length">
                <div v-for="g in nodeGroupsOf(row.id)" :key="g.nodeOrder" class="node-row">
                  <span class="node-order">{{ g.nodeOrder }}</span>
                  <span class="node-mode">{{ g.mode === 'AND' ? '会签' : '或签' }}</span>
                  <span v-for="n in g.nodes" :key="n.id" class="node-chip">
                    {{ approverTypeLabel(n.approverType) }}
                    <b>{{ n.approverDisplay || n.approverRef }}</b>
                  </span>
                </div>
              </template>
              <div v-else-if="nodesMap[row.id]" class="node-empty">
                该流程暂无节点{{ row.riskLevel === 'LOW' ? '（LOW 免审）' : '——当前等级的工单将无法提交' }}
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="180">
          <template #default="{ row }"><span class="cell-row-title">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column label="范围" min-width="160">
          <template #default="{ row }">
            <el-tag v-if="row.instanceId == null" effect="light" type="primary" size="small">全局</el-tag>
            <el-tag v-else effect="light" type="warning" size="small">
              实例 · {{ row.instanceName || instanceName(row.instanceId) }}
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
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-switch
              v-if="session.can(API.FLOW_UPDATE)"
              v-model="row.enabled"
              :loading="row.toggling"
              active-text="开"
              inactive-text="关"
              inline-prompt
              @change="toggleFlow(row)"
            />
            <el-switch v-else :model-value="!!row.enabled" disabled active-text="开" inactive-text="关" inline-prompt />
          </template>
        </el-table-column>
        <el-table-column v-if="canEdit" label="操作" width="190" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="session.can(API.FLOW_NODES_REPLACE)"
              link
              type="primary"
              size="small"
              @click="openNodeDialog(row)"
            >编辑节点</el-button>
            <el-button
              v-if="session.can(API.FLOW_UPDATE)"
              link
              type="primary"
              size="small"
              @click="openEditDialog(row)"
            >编辑</el-button>
            <el-button
              v-if="session.can(API.FLOW_DELETE)"
              link
              type="danger"
              size="small"
              @click="removeFlow(row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增流程：范围 + 风险等级创建后不可改 -->
    <el-dialog v-model="createDialog.visible" title="新增流程" width="520px" destroy-on-close>
      <el-form :model="createDialog.form" label-width="96px">
        <el-form-item label="名称" required>
          <el-input v-model="createDialog.form.name" placeholder="如：订单库 · HIGH" />
        </el-form-item>
        <el-form-item label="范围">
          <el-radio-group v-model="createDialog.form.scope">
            <el-radio value="GLOBAL">全局</el-radio>
            <el-radio value="INSTANCE">指定实例</el-radio>
          </el-radio-group>
          <div class="form-tip">实例专用链的节点会追加在全局链之后，只能加严</div>
        </el-form-item>
        <el-form-item v-if="createDialog.form.scope === 'INSTANCE'" label="实例" required>
          <el-select v-model="createDialog.form.instanceId" placeholder="选择实例" style="width: 100%">
            <el-option v-for="ins in instances" :key="ins.id" :label="ins.name" :value="ins.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="风险等级">
          <el-select v-model="createDialog.form.riskLevel" style="width: 100%">
            <el-option v-for="(meta, key) in RISK_LEVEL" :key="key" :label="meta.label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="createDialog.form.enabled" active-text="开" inactive-text="关" inline-prompt />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.saving" @click="saveCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 编辑流程：仅名称与启用 -->
    <el-dialog v-model="editDialog.visible" title="编辑流程" width="460px" destroy-on-close>
      <el-form :model="editDialog.form" label-width="96px">
        <el-form-item label="范围">
          <span class="readonly-text">{{ editDialog.scopeText }}</span>
        </el-form-item>
        <el-form-item label="风险等级">
          <span class="readonly-text">{{ riskMeta(editDialog.riskLevel).label }}</span>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="editDialog.form.name" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editDialog.form.enabled" active-text="开" inactive-text="关" inline-prompt />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="editDialog.saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 节点编辑器：按组（node_order）编辑，组内会签 / 或签 -->
    <el-dialog
      v-model="nodeDialog.visible"
      :title="`编辑审批节点：${nodeDialog.flowName}`"
      width="720px"
      destroy-on-close
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="node-alert"
        title="同一组内的审批人按「会签（全部通过）」或「或签（任一通过）」判定；组按顺序依次审批。"
      />

      <div v-loading="nodeDialog.loading" class="group-editor">
        <div v-for="(g, gi) in nodeDialog.groups" :key="gi" class="group-card">
          <div class="group-head">
            <span class="group-order">第 {{ gi + 1 }} 组</span>
            <el-radio-group v-model="g.mode" size="small">
              <el-radio-button value="OR">或签</el-radio-button>
              <el-radio-button value="AND">会签</el-radio-button>
            </el-radio-group>
            <div class="grow" />
            <el-button link type="danger" size="small" @click="removeGroup(gi)">删除本组</el-button>
          </div>

          <div v-for="(m, mi) in g.members" :key="mi" class="member-row">
            <el-select v-model="m.approverType" style="width: 140px" @change="onTypeChange(m)">
              <el-option
                v-for="(label, key) in APPROVER_TYPE"
                :key="key"
                :label="label"
                :value="key"
              />
            </el-select>

            <el-select
              v-if="m.approverType === 'USER'"
              v-model="m.approverRef"
              filterable
              placeholder="选择审批人"
              style="width: 240px"
            >
              <el-option
                v-for="u in directoryUsers"
                :key="u.id"
                :label="`${u.displayName || u.username}（${u.username}）`"
                :value="String(u.id)"
              />
            </el-select>
            <el-select
              v-else-if="m.approverType === 'ROLE'"
              v-model="m.approverRef"
              filterable
              placeholder="选择角色"
              style="width: 240px"
            >
              <el-option
                v-for="r in directoryRoles"
                :key="r.code"
                :label="`${r.name}（${r.code}）`"
                :value="r.code"
              />
            </el-select>
            <span v-else class="member-fixed">提交时解析为该实例的负责人</span>

            <el-button link type="danger" size="small" @click="removeMember(g, mi)">移除</el-button>
          </div>

          <el-button link type="primary" size="small" @click="addMember(g)">+ 添加审批人</el-button>
        </div>

        <div v-if="!nodeDialog.groups.length" class="group-empty">
          暂无节点（LOW 等级留空即免审；其余等级留空将导致工单无法提交）
        </div>
        <el-button class="add-group" size="small" @click="addGroup">+ 添加审批组</el-button>
      </div>

      <template #footer>
        <el-button @click="nodeDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="nodeDialog.saving" @click="saveNodes">保存节点</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { API } from '../../api/capabilities'
import { riskMeta, RISK_LEVEL, APPROVER_TYPE, approverTypeLabel } from '../../constants'
import { useSessionStore } from '../../stores/session'

const session = useSessionStore()

/** 任一写操作可见即显示操作列（真实鉴权以后端为准） */
const canEdit = computed(
  () =>
    session.can(API.FLOW_CREATE) ||
    session.can(API.FLOW_UPDATE) ||
    session.can(API.FLOW_DELETE) ||
    session.can(API.FLOW_NODES_REPLACE)
)

// ---- 流程列表 ----
const flows = ref([])
const flowsLoading = ref(false)
const nodesMap = reactive({})
const instances = ref([])
const directoryUsers = ref([])
const directoryRoles = ref([])

async function loadFlows() {
  flowsLoading.value = true
  try {
    flows.value = (await api.listFlows()) || []
  } finally {
    flowsLoading.value = false
  }
}

/** 选项来源：实例（范围选择）、用户 / 角色（节点审批人） */
async function loadOptions() {
  const [insts, users, roles] = await Promise.all([
    api.listInstances().catch(() => []),
    api.listDirectoryUsers().catch(() => []),
    api.listDirectoryRoles().catch(() => [])
  ])
  instances.value = insts || []
  directoryUsers.value = users || []
  directoryRoles.value = roles || []
}

onMounted(() => {
  loadFlows()
  loadOptions()
})

function instanceName(id) {
  return instances.value.find((i) => i.id === id)?.name || `实例 ${id}`
}

async function loadNodes(flowId, force = false) {
  if (nodesMap[flowId] && !force) return
  nodesMap[flowId] = (await api.listFlowNodes(flowId).catch(() => [])) || []
}

function onExpandChange(row, expandedRows) {
  if (expandedRows.some((r) => r.id === row.id)) loadNodes(row.id)
}

/** 展开行展示：按 node_order 分组（组内 mode 一致） */
function nodeGroupsOf(flowId) {
  const nodes = nodesMap[flowId] || []
  const map = new Map()
  for (const n of [...nodes].sort((a, b) => a.nodeOrder - b.nodeOrder)) {
    if (!map.has(n.nodeOrder)) map.set(n.nodeOrder, { nodeOrder: n.nodeOrder, mode: n.mode, nodes: [] })
    map.get(n.nodeOrder).nodes.push(n)
  }
  return [...map.values()]
}

// ---- 新增流程 ----
const createDialog = reactive({
  visible: false,
  saving: false,
  form: { name: '', scope: 'GLOBAL', instanceId: null, riskLevel: 'MEDIUM', enabled: true }
})

function openCreateDialog() {
  Object.assign(createDialog.form, {
    name: '',
    scope: 'GLOBAL',
    instanceId: null,
    riskLevel: 'MEDIUM',
    enabled: true
  })
  createDialog.visible = true
}

async function saveCreate() {
  const f = createDialog.form
  if (!f.name.trim()) {
    ElMessage.warning('请填写流程名称')
    return
  }
  if (f.scope === 'INSTANCE' && f.instanceId == null) {
    ElMessage.warning('请选择实例')
    return
  }
  createDialog.saving = true
  try {
    await api.createFlow({
      name: f.name.trim(),
      instanceId: f.scope === 'INSTANCE' ? f.instanceId : null,
      riskLevel: f.riskLevel,
      enabled: f.enabled
    })
    ElMessage.success('流程已创建，可继续「编辑节点」配置审批人')
    createDialog.visible = false
    await loadFlows()
  } catch {
    // 拦截器已提示错误（同范围 + 风险等级重复 → 409 DUPLICATE_FLOW）
  } finally {
    createDialog.saving = false
  }
}

// ---- 编辑流程（名称 / 启用）----
const editDialog = reactive({
  visible: false,
  saving: false,
  id: null,
  riskLevel: 'LOW',
  scopeText: '',
  form: { name: '', enabled: true }
})

function openEditDialog(row) {
  editDialog.id = row.id
  editDialog.riskLevel = row.riskLevel
  editDialog.scopeText = row.instanceId == null
    ? '全局'
    : `实例 · ${row.instanceName || instanceName(row.instanceId)}`
  Object.assign(editDialog.form, { name: row.name || '', enabled: !!row.enabled })
  editDialog.visible = true
}

async function saveEdit() {
  if (!editDialog.form.name.trim()) {
    ElMessage.warning('请填写流程名称')
    return
  }
  editDialog.saving = true
  try {
    await api.updateFlow(editDialog.id, {
      name: editDialog.form.name.trim(),
      enabled: editDialog.form.enabled
    })
    ElMessage.success('流程已更新')
    editDialog.visible = false
    await loadFlows()
  } catch {
    // 拦截器已提示错误
  } finally {
    editDialog.saving = false
  }
}

async function removeFlow(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除流程「${row.name}」？该流程下的全部节点将一并删除，此操作不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await api.deleteFlow(row.id)
    ElMessage.success('流程已删除')
    delete nodesMap[row.id]
    await loadFlows()
  } catch {
    // 拦截器已提示错误
  }
}

/**
 * 启用列开关：确认后提交 name + enabled（PUT /config/flows/{id} 只收这两个字段）。
 * 后端更新后 reload 审批链，即时生效，无需重启。
 */
async function toggleFlow(row) {
  const target = !!row.enabled
  const action = target ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      `确认${action}流程「${row.name}」？${target ? '启用后该流程参与审批路由。' : '停用后该流程不再参与审批路由。'}`,
      `${action}流程`,
      { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' }
    )
  } catch {
    row.enabled = !target // 用户取消，回滚开关显示
    return
  }
  row.toggling = true
  try {
    await api.updateFlow(row.id, { name: row.name, enabled: target })
    ElMessage.success(`流程已${action}`)
  } catch {
    row.enabled = !target // 请求失败，回滚开关显示
  } finally {
    row.toggling = false
  }
}

// ---- 节点编辑器（整体替换）----
const nodeDialog = reactive({
  visible: false,
  loading: false,
  saving: false,
  flowId: null,
  flowName: '',
  groups: []
})

async function openNodeDialog(row) {
  nodeDialog.flowId = row.id
  nodeDialog.flowName = row.name
  nodeDialog.groups = []
  nodeDialog.visible = true
  nodeDialog.loading = true
  try {
    await loadNodes(row.id, true)
    nodeDialog.groups = nodeGroupsOf(row.id).map((g) => ({
      mode: g.mode || 'OR',
      members: g.nodes.map((n) => ({
        approverType: n.approverType,
        approverRef: n.approverType === 'INSTANCE_OWNER' ? '*' : String(n.approverRef ?? '')
      }))
    }))
  } finally {
    nodeDialog.loading = false
  }
}

function addGroup() {
  nodeDialog.groups.push({ mode: 'OR', members: [{ approverType: 'ROLE', approverRef: '' }] })
}

function removeGroup(index) {
  nodeDialog.groups.splice(index, 1)
}

function addMember(group) {
  group.members.push({ approverType: 'ROLE', approverRef: '' })
}

function removeMember(group, index) {
  group.members.splice(index, 1)
}

/** 类型切换后清空引用；实例负责人固定为 `*` */
function onTypeChange(member) {
  member.approverRef = member.approverType === 'INSTANCE_OWNER' ? '*' : ''
}

async function saveNodes() {
  // 组内 mode 一致、node_order 从 1 连续由「按组编辑」天然保证；此处只校验引用完整
  for (const [gi, g] of nodeDialog.groups.entries()) {
    if (!g.members.length) {
      ElMessage.warning(`第 ${gi + 1} 组没有审批人，请添加或删除该组`)
      return
    }
    for (const m of g.members) {
      if (m.approverType !== 'INSTANCE_OWNER' && !String(m.approverRef || '').trim()) {
        ElMessage.warning(`第 ${gi + 1} 组存在未选择的审批人`)
        return
      }
    }
  }
  const payload = nodeDialog.groups.flatMap((g, gi) =>
    g.members.map((m) => ({
      nodeOrder: gi + 1,
      approverType: m.approverType,
      approverRef: m.approverType === 'INSTANCE_OWNER' ? '*' : String(m.approverRef).trim(),
      mode: g.mode
    }))
  )
  // 整体替换不可逆：保存前显式确认，避免误操作覆盖既有节点
  try {
    await ElMessageBox.confirm(
      '保存将整体替换该流程的全部审批节点（原节点将被覆盖），确认保存？',
      '保存节点',
      { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  nodeDialog.saving = true
  try {
    await api.replaceFlowNodes(nodeDialog.flowId, payload)
    ElMessage.success('审批节点已保存')
    nodeDialog.visible = false
    await loadNodes(nodeDialog.flowId, true)
  } catch {
    // 拦截器已提示错误（角色无有效成员 / 用户停用等保存期校验）
  } finally {
    nodeDialog.saving = false
  }
}
</script>

<style scoped>
.config-page { display: flex; flex-direction: column; gap: 16px; }

.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 18px; font-weight: 700; color: var(--sa-text); }
.page-desc { font-size: 12.5px; color: var(--sa-text-3); margin-top: 3px; }
.role-tag {
  font-size: 11px; color: var(--sa-text-2); background: var(--sa-surface-3);
  padding: 3px 10px; border-radius: 6px; font-weight: 600;
}

.pane-card :deep(.el-card__body) { padding: 8px 18px 18px; }

.pane-toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin: 12px 0; }
.toolbar-desc { font-size: 12px; color: var(--sa-text-3); }
.toolbar-action { margin-left: auto; }

.cell-row-title { font-size: 13px; color: var(--sa-text); font-weight: 600; }
.readonly-text { font-size: 12.5px; color: var(--sa-text-2); }
.form-tip { font-size: 11.5px; color: var(--sa-text-3); line-height: 1.5; margin-top: 4px; }

/* ---- 展开行：节点组 ---- */
.nodes-panel { padding: 8px 12px 8px 8px; min-height: 40px; }
.node-row {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 6px 0;
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
.node-empty { font-size: 12px; color: var(--sa-text-3); padding: 6px 0; }

/* ---- 节点编辑器 ---- */
.node-alert { margin-bottom: 14px; }
.group-editor { max-height: 420px; overflow-y: auto; padding-right: 4px; }
.group-card {
  background: var(--sa-surface-2);
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius-sm);
  padding: 12px 14px;
}
.group-card + .group-card { margin-top: 10px; }
.group-head { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.group-order { font-size: 12px; font-weight: 650; color: var(--sa-text); }
.grow { flex: 1; }
.member-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.member-fixed { font-size: 12px; color: var(--sa-text-3); width: 240px; }
.group-empty { font-size: 12px; color: var(--sa-text-3); padding: 10px 2px; }
.add-group { margin-top: 10px; }
</style>
