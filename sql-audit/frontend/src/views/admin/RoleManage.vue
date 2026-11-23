<template>
  <div class="config-page">
    <div class="page-head">
      <div>
        <div class="page-title">角色管理</div>
        <div class="page-desc">自定义角色 · 权重决定审批链「谁更严」（仅管理员）</div>
      </div>
      <span class="role-tag">仅管理员可访问</span>
    </div>

    <el-card class="pane-card" shadow="never">
      <div class="pane-toolbar">
        <span class="toolbar-desc">内置角色（DEV/LEAD/DBA/ADMIN）不可删除、code 不可改；自定义角色可增删改</span>
        <el-button type="primary" size="small" class="toolbar-action" @click="openDialog()">新增角色</el-button>
      </div>

      <el-table v-loading="loading" :data="roles" class="pane-table" empty-text="暂无角色">
        <el-table-column label="code" width="150">
          <template #default="{ row }"><span class="cell-mono mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="名称" width="160">
          <template #default="{ row }"><span class="cell-row-title">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column label="说明" min-width="220">
          <template #default="{ row }"><span class="cell-dim">{{ row.description || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="权重" width="90" align="center">
          <template #default="{ row }"><span class="cell-dim">{{ row.rank }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="isBuiltIn(row.code)" effect="light" size="small" type="info">内置</el-tag>
            <el-tag v-else effect="light" size="small" type="success">自定义</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDialog(row)">编辑</el-button>
            <el-button v-if="!isBuiltIn(row.code)" link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.editingId ? '编辑角色' : '新增角色'"
      width="480px"
      destroy-on-close
    >
      <el-form :model="dialog.form" label-width="80px">
        <el-form-item label="code" required>
          <el-input v-model="dialog.form.code" :disabled="dialog.editingId != null" placeholder="如 QA / PM" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="dialog.form.name" placeholder="角色显示名" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="dialog.form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="权重">
          <el-input-number v-model="dialog.form.rank" :min="0" style="width: 140px" />
          <span class="rank-hint">数字越大越「严」；LEAD=1 DBA=2 ADMIN=3</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { ROLE_LABELS } from '../../constants'

const roles = ref([])
const loading = ref(false)

function isBuiltIn(code) {
  return Object.prototype.hasOwnProperty.call(ROLE_LABELS, code)
}

async function load() {
  loading.value = true
  try {
    roles.value = await api.listRoles()
  } finally {
    loading.value = false
  }
}

onMounted(load)

const dialog = reactive({
  visible: false,
  editingId: null,
  saving: false,
  form: { code: '', name: '', description: '', rank: 0 }
})

function openDialog(row = null) {
  dialog.editingId = row ? row.id : null
  Object.assign(dialog.form, {
    code: row?.code || '',
    name: row?.name || '',
    description: row?.description || '',
    rank: row?.rank ?? 0
  })
  dialog.visible = true
}

async function save() {
  if (!dialog.form.code.trim()) {
    ElMessage.warning('请填写角色 code')
    return
  }
  dialog.saving = true
  try {
    if (dialog.editingId) {
      await api.updateRole(dialog.editingId, {
        name: dialog.form.name,
        description: dialog.form.description,
        rank: dialog.form.rank
      })
      ElMessage.success('角色已更新')
    } else {
      await api.createRole({
        code: dialog.form.code.trim(),
        name: dialog.form.name,
        description: dialog.form.description,
        rank: dialog.form.rank
      })
      ElMessage.success('角色已创建')
    }
    dialog.visible = false
    await load()
  } catch {
    // 拦截器已提示错误
  } finally {
    dialog.saving = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除角色「${row.name}」？仍持有该角色的用户与权限映射将被清理。`,
      '删除角色',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await api.deleteRole(row.id)
    ElMessage.success('角色已删除')
    await load()
  } catch {
    // 拦截器已提示错误
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

.rank-hint { margin-left: 10px; font-size: 11px; color: var(--sa-text-3); }
</style>
