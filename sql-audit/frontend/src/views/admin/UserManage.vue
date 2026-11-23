<template>
  <div class="config-page">
    <div class="page-head">
      <div>
        <div class="page-title">用户管理</div>
        <div class="page-desc">本地账号 · 角色分配 · 密码重置（全程写审计）</div>
      </div>
      <span class="role-tag">当前角色：{{ roleLabel }} · 仅管理员可访问</span>
    </div>

    <el-card class="pane-card" shadow="never">
      <div class="pane-toolbar">
        <span class="toolbar-desc">本地账号 · 角色分配 · 密码重置（全程写审计）</span>
        <el-button
          type="primary"
          size="small"
          class="toolbar-action"
          @click="openCreateUserDialog()"
        >新增用户</el-button>
      </div>

      <el-table v-loading="usersLoading" :data="users" class="pane-table" empty-text="暂无用户">
        <el-table-column label="用户名" width="160">
          <template #default="{ row }"><span class="cell-mono mono">{{ row.username }}</span></template>
        </el-table-column>
        <el-table-column label="显示名" width="140">
          <template #default="{ row }"><span class="cell-row-title">{{ row.displayName || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="邮箱" min-width="180">
          <template #default="{ row }"><span class="cell-dim">{{ row.email || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="来源" width="90">
          <template #default="{ row }">
            <el-tag effect="light" :type="row.source === 'LOCAL' ? 'info' : 'primary'" size="small">
              {{ row.source === 'LOCAL' ? '本地' : row.source }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag effect="light" :type="row.status === 'ACTIVE' ? 'success' : 'danger'" size="small">
              {{ row.status === 'ACTIVE' ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="r in row.roles" :key="r" effect="light" size="small" class="role-chip">
              {{ roleNameByCode[r] || r }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEditUserDialog(row)">编辑</el-button>
            <el-button link type="warning" size="small" @click="openResetPwdDialog(row)">重置密码</el-button>
            <el-button link type="primary" size="small" @click="openAssignRolesDialog(row)">分配角色</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增用户 dialog -->
    <el-dialog
      v-model="createUserDialogVisible"
      title="新增用户"
      width="480px"
      destroy-on-close
    >
      <el-form :model="createUserForm" label-width="90px">
        <el-form-item label="用户名" required>
          <el-input v-model="createUserForm.username" placeholder="登录名（唯一）" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="createUserForm.displayName" placeholder="可选，默认同用户名" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="createUserForm.email" placeholder="可选" />
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="createUserForm.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="createUserForm.roles" multiple style="width: 100%" placeholder="选择角色">
            <el-option v-for="r in roles" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createUserDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="userSaving" @click="saveCreateUser">创建</el-button>
      </template>
    </el-dialog>

    <!-- 编辑用户 dialog -->
    <el-dialog
      v-model="editUserDialogVisible"
      title="编辑用户"
      width="440px"
      destroy-on-close
    >
      <el-form :model="editUserForm" label-width="90px">
        <el-form-item label="显示名">
          <el-input v-model="editUserForm.displayName" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="editUserForm.email" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editUserForm.status" style="width: 100%">
            <el-option label="正常（ACTIVE）" value="ACTIVE" />
            <el-option label="停用（DISABLED）" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editUserDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="userSaving" @click="saveEditUser">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 dialog -->
    <el-dialog
      v-model="resetPwdDialogVisible"
      :title="`重置密码：${resetPwdForm.username}`"
      width="400px"
      destroy-on-close
    >
      <el-form :model="resetPwdForm" label-width="90px">
        <el-form-item label="新密码" required>
          <el-input v-model="resetPwdForm.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetPwdDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="userSaving" @click="saveResetPwd">确认重置</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色 dialog -->
    <el-dialog
      v-model="assignRolesDialogVisible"
      :title="`分配角色：${assignRolesForm.username}`"
      width="400px"
      destroy-on-close
    >
      <el-form :model="assignRolesForm" label-width="90px">
        <el-form-item label="角色">
          <el-select v-model="assignRolesForm.roles" multiple style="width: 100%" placeholder="选择角色">
            <el-option v-for="r in roles" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignRolesDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="userSaving" @click="saveAssignRoles">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api'
import { ROLE_LABELS } from '../../constants'
import { useSessionStore } from '../../stores/session'

const session = useSessionStore()

const roleLabel = computed(
  () => session.roles.map((r) => ROLE_LABELS[r] || r).join('、') || '未知角色'
)

// ---- 角色（动态，含自定义角色）----
const roles = ref([])
const roleNameByCode = computed(
  () => Object.fromEntries(roles.value.map((r) => [r.code, r.name]))
)

async function loadRoles() {
  roles.value = await api.listRoles()
}

// ---- 用户管理（ADMIN）----
const users = ref([])
const usersLoading = ref(false)
const userSaving = ref(false)

async function loadUsers() {
  usersLoading.value = true
  try {
    users.value = await api.listUsers()
  } finally {
    usersLoading.value = false
  }
}

onMounted(() => {
  loadUsers()
  loadRoles()
})

// 新增用户
const createUserDialogVisible = ref(false)
const createUserForm = reactive({ username: '', displayName: '', email: '', password: '', roles: [] })

function openCreateUserDialog() {
  Object.assign(createUserForm, { username: '', displayName: '', email: '', password: '', roles: [] })
  createUserDialogVisible.value = true
}

async function saveCreateUser() {
  if (!createUserForm.username.trim()) {
    ElMessage.warning('请填写用户名')
    return
  }
  if (!createUserForm.password) {
    ElMessage.warning('请填写初始密码')
    return
  }
  userSaving.value = true
  try {
    await api.createUser({ ...createUserForm })
    ElMessage.success('用户已创建')
    createUserDialogVisible.value = false
    await loadUsers()
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    userSaving.value = false
  }
}

// 编辑用户（displayName / email / status）
const editUserDialogVisible = ref(false)
const editUserForm = reactive({ id: null, displayName: '', email: '', status: 'ACTIVE' })

function openEditUserDialog(row) {
  Object.assign(editUserForm, {
    id: row.id,
    displayName: row.displayName || '',
    email: row.email || '',
    status: row.status || 'ACTIVE'
  })
  editUserDialogVisible.value = true
}

async function saveEditUser() {
  userSaving.value = true
  try {
    await api.updateUser(editUserForm.id, {
      displayName: editUserForm.displayName,
      email: editUserForm.email,
      status: editUserForm.status
    })
    ElMessage.success('用户已更新')
    editUserDialogVisible.value = false
    await loadUsers()
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    userSaving.value = false
  }
}

// 重置密码
const resetPwdDialogVisible = ref(false)
const resetPwdForm = reactive({ id: null, username: '', password: '' })

function openResetPwdDialog(row) {
  Object.assign(resetPwdForm, { id: row.id, username: row.username, password: '' })
  resetPwdDialogVisible.value = true
}

async function saveResetPwd() {
  if (!resetPwdForm.password) {
    ElMessage.warning('请填写新密码')
    return
  }
  userSaving.value = true
  try {
    await api.resetPassword(resetPwdForm.id, resetPwdForm.password)
    ElMessage.success('密码已重置')
    resetPwdDialogVisible.value = false
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    userSaving.value = false
  }
}

// 分配角色
const assignRolesDialogVisible = ref(false)
const assignRolesForm = reactive({ id: null, username: '', roles: [] })

function openAssignRolesDialog(row) {
  Object.assign(assignRolesForm, {
    id: row.id,
    username: row.username,
    roles: row.roles ? [...row.roles] : []
  })
  assignRolesDialogVisible.value = true
}

async function saveAssignRoles() {
  userSaving.value = true
  try {
    await api.assignRoles(assignRolesForm.id, assignRolesForm.roles)
    ElMessage.success('角色已更新')
    assignRolesDialogVisible.value = false
    await loadUsers()
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    userSaving.value = false
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

.cell-mono { font-family: var(--sa-font-mono); font-size: 12px; color: var(--sa-text); font-weight: 600; }
.cell-row-title { font-size: 13px; color: var(--sa-text); font-weight: 600; }
.cell-dim { color: var(--sa-text-2); font-size: 12px; }

.role-chip { margin-right: 4px; }
</style>
