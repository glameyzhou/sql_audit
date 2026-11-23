<template>
  <div class="config-page">
    <div class="page-head">
      <div>
        <div class="page-title">实例管理</div>
        <div class="page-desc">生产库为核心资产：默认最小权限、按需授权、到期自动回收</div>
      </div>
      <span class="role-tag">当前角色：{{ roleLabel }} · {{ canEditInstances ? '可编辑' : '只读' }}</span>
    </div>

    <el-card class="pane-card" shadow="never">
      <div class="pane-toolbar">
        <span class="toolbar-desc">生产库为核心资产：默认最小权限、按需授权、到期自动回收</span>
        <el-button
          v-if="canEditInstances"
          type="primary"
          size="small"
          class="toolbar-action"
          @click="openInstanceDialog()"
        >新增实例</el-button>
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
        <el-table-column v-if="canEditInstances" label="操作" width="130" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openInstanceDialog(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeInstance(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 实例编辑 dialog -->
    <el-dialog
      v-model="instanceDialogVisible"
      :title="instanceEditingId ? '编辑实例' : '新增实例'"
      width="520px"
      destroy-on-close
    >
      <el-form :model="instanceForm" label-width="96px">
        <el-form-item label="数据库">
          <el-input v-model="instanceForm.name" placeholder="实例名称" />
        </el-form-item>
        <el-form-item label="方言">
          <el-select v-model="instanceForm.dialect" placeholder="MySQL / PostgreSQL / IoTDB" style="width: 100%">
            <!-- value 与后端 Dialect 枚举一致（MYSQL/PG/IOTDB），POSTGRESQL 等别名不入库 -->
            <el-option label="MySQL" value="MYSQL" />
            <el-option label="PostgreSQL" value="PG" />
            <el-option label="IoTDB" value="IOTDB" />
          </el-select>
        </el-form-item>
        <el-form-item label="环境">
          <el-select v-model="instanceForm.environment" style="width: 100%">
            <el-option label="开发（dev）" value="dev" />
            <el-option label="测试（test）" value="test" />
            <el-option label="生产（prod）" value="prod" />
          </el-select>
        </el-form-item>
        <el-form-item label="Host">
          <el-input v-model="instanceForm.host" placeholder="如 db.example.com" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="instanceForm.port" :min="1" :max="65535" style="width: 100%" />
        </el-form-item>
        <el-form-item label="默认 Schema">
          <el-input v-model="instanceForm.defaultSchema" placeholder="默认 schema" />
        </el-form-item>

        <!-- 数据库凭据：所有环境一致，随实例加密保存（RO_META + RW_EXEC），同一组账号 -->
        <el-divider content-position="left">数据库凭据（随实例加密保存，所有环境一致）</el-divider>
        <el-form-item label="账号">
          <el-input v-model="credForm.username" placeholder="平台连接该实例使用的账号" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="credForm.password"
            type="password"
            show-password
            :placeholder="instanceEditingId ? '留空表示不修改凭据' : '平台连接该实例使用的密码'"
          />
        </el-form-item>
        <el-form-item>
          <el-button :loading="testConnecting" @click="testInstanceConnection">测试连接</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="instanceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="instanceSaving" @click="saveInstance">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { ROLE_LABELS, envMeta } from '../../constants'
import { useSessionStore } from '../../stores/session'

const session = useSessionStore()

const roleLabel = computed(
  () => session.roles.map((r) => ROLE_LABELS[r] || r).join('、') || '未知角色'
)

// ---- 权限（真实鉴权以后端为准，此处仅控制可见性）----
const isAdmin = computed(() => session.roles.includes('ADMIN'))
/** 实例：仅 ADMIN 可写 */
const canEditInstances = computed(() => isAdmin.value)

// ---- 实例 ----
const instances = ref([])
const instancesLoading = ref(false)

async function loadInstances() {
  instancesLoading.value = true
  try {
    instances.value = await api.listInstances()
  } finally {
    instancesLoading.value = false
  }
}

onMounted(loadInstances)

// ---- 实例 CRUD ----
const instanceDialogVisible = ref(false)
const instanceSaving = ref(false)
const instanceEditingId = ref(null)
// Owner 由后端自动取当前登录用户，前端不再提交 ownerId
const instanceForm = reactive({
  name: '',
  dialect: '',
  environment: 'test',
  host: '',
  port: 3306,
  defaultSchema: '',
  status: 'ACTIVE'
})

// 数据库凭据：所有环境一致，保存时随实例加密落库（后端写 RO_META + RW_EXEC 两条）
const credForm = reactive({ username: '', password: '' })
/** 编辑时回填的账号名（用于判断「只改账号没给密码」）；新建为空 */
const credOriginalUsername = ref('')
const testConnecting = ref(false)

async function openInstanceDialog(row = null) {
  instanceEditingId.value = row ? row.id : null
  Object.assign(instanceForm, {
    name: row?.name || '',
    // 历史数据兼容：旧值 POSTGRESQL / staging 分别映射为规范值 PG / test
    dialect: row?.dialect === 'POSTGRESQL' ? 'PG' : row?.dialect || '',
    environment: row?.environment === 'staging' ? 'test' : row?.environment || 'test',
    host: row?.host || '',
    port: row?.port ?? 3306,
    defaultSchema: row?.defaultSchema || '',
    status: row?.status || 'ACTIVE'
  })
  Object.assign(credForm, { username: '', password: '' })
  credOriginalUsername.value = ''
  instanceDialogVisible.value = true
  if (row) {
    // 编辑回填账号名（密码永不回显；留空 = 不修改）
    try {
      const list = await api.getInstanceCredentials(row.id)
      const meta = (list || []).find((c) => c.accountType === 'RO_META') || (list || [])[0]
      if (meta?.username) {
        credForm.username = meta.username
        credOriginalUsername.value = meta.username
      }
    } catch {
      // 凭据概要读取失败不阻塞编辑，账号可重新填写
    }
  }
}

/**
 * 测试连接：后端一律 HTTP 200 返回 { ok, message }（不抛 500）——
 * 成功走 success toast，ok:false 走 error toast（红色），不能一律绿色。
 */
async function testInstanceConnection() {
  if (!instanceForm.host || !instanceForm.port) {
    ElMessage.warning('请先填写 Host 和端口')
    return
  }
  testConnecting.value = true
  try {
    const data = await api.testConnection({
      dialect: instanceForm.dialect,
      host: instanceForm.host,
      port: instanceForm.port,
      username: credForm.username.trim(),
      password: credForm.password,
      database: instanceForm.defaultSchema
    })
    if (data?.ok === false) {
      ElMessage.error(data?.message || '连接失败')
      return
    }
    ElMessage.success(data?.message || '连接成功')
  } catch {
    // 网络/鉴权失败提示已由响应拦截器统一展示
  } finally {
    testConnecting.value = false
  }
}

async function saveInstance() {
  // 凭据校验：新建必须成对填写（所有环境一致，不再有 SQL 种子特例）；
  // 编辑时密码留空 = 保留原凭据（密码不回显），此时账号名不参与提交。
  const hasUser = !!credForm.username.trim()
  const hasPwd = !!credForm.password
  if (!instanceEditingId.value) {
    if (!hasUser || !hasPwd) {
      ElMessage.warning('请填写数据库账号和密码（平台连接该实例需要凭据）')
      return
    }
  } else if (hasPwd && !hasUser) {
    ElMessage.warning('请填写凭据账号')
    return
  } else if (!hasPwd && hasUser !== credOriginalUsername.value) {
    ElMessage.warning('修改凭据账号需同时填写新密码')
    return
  }
  instanceSaving.value = true
  try {
    const payload = { ...instanceForm }
    if (hasPwd) {
      payload.username = credForm.username.trim()
      payload.password = credForm.password
    }
    if (instanceEditingId.value) {
      await api.updateInstance(instanceEditingId.value, payload)
      ElMessage.success('实例已更新')
    } else {
      await api.createInstance(payload)
      ElMessage.success('实例已创建（凭据已加密保存）')
    }
    instanceDialogVisible.value = false
    await loadInstances()
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    instanceSaving.value = false
  }
}

async function removeInstance(row) {
  if (!(await confirmDelete(`实例「${row.name}」`))) return
  try {
    await api.deleteInstance(row.id)
    ElMessage.success('实例已删除')
    await loadInstances()
  } catch {
    // 错误提示已由响应拦截器统一处理
  }
}

/** 删除二次确认（返回是否继续） */
async function confirmDelete(label) {
  try {
    await ElMessageBox.confirm(`确认删除 ${label}？此操作不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    return true
  } catch {
    return false
  }
}

// 环境标签类型：对齐 ENV 三档（dev 灰 / test 黄 / prod 红）；staging 旧值兼容显示为「测试」
function envType(env) {
  return envMeta(env).type
}

function envLabel(env) {
  if (env === 'ALL') return '全部'
  return envMeta(env).label
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

.cell-mono { font-family: var(--sa-font-mono); font-size: 12px; color: var(--sa-text); font-weight: 600; }
.cell-row-title { font-size: 13px; color: var(--sa-text); font-weight: 600; }
.cell-dim { color: var(--sa-text-2); font-size: 12px; }
</style>
