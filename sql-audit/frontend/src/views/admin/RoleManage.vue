<template>
  <div class="config-page">
    <div class="page-head">
      <div>
        <div class="page-title">角色管理</div>
        <div class="page-desc">功能权限（能调哪些接口）× 数据范围（能碰哪些实例）两轴独立配置</div>
      </div>
      <span class="role-tag">内置角色不可删除、code 不可改</span>
    </div>

    <el-card class="pane-card" shadow="never">
      <div class="pane-toolbar">
        <span class="toolbar-desc">
          权限点目录由系统维护（见「权限目录」页），此处为角色勾选权限点与可访问实例
        </span>
        <el-button
          v-if="session.can(API.ROLE_CREATE)"
          type="primary"
          size="small"
          class="toolbar-action"
          @click="openDialog()"
        >新增角色</el-button>
      </div>

      <el-table v-loading="loading" :data="roles" class="pane-table" empty-text="暂无角色">
        <el-table-column label="code" min-width="150">
          <template #default="{ row }"><span class="cell-mono mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="名称" min-width="150">
          <template #default="{ row }"><span class="cell-row-title">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column label="说明" min-width="200">
          <template #default="{ row }"><span class="cell-dim">{{ row.description || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="功能权限" width="110" align="center">
          <template #default="{ row }">
            <span class="cell-dim">{{ (row.permissionIds || []).length }} 项</span>
          </template>
        </el-table-column>
        <el-table-column label="数据范围" min-width="160">
          <template #default="{ row }">
            <el-tag effect="light" :type="scopeMeta(row.instanceScope).type" size="small">
              {{ scopeMeta(row.instanceScope).label }}
            </el-tag>
            <span v-if="row.instanceScope === 'SELECTED'" class="cell-dim scope-detail">
              {{ instanceNames(row.instanceIds) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="用户数" width="90" align="center">
          <template #default="{ row }"><span class="cell-dim">{{ row.userCount ?? '-' }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.builtin" effect="light" size="small" type="info">内置</el-tag>
            <el-tag v-else effect="light" size="small" type="success">自定义</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="session.can(API.ROLE_UPDATE)"
              link
              type="primary"
              size="small"
              @click="openDialog(row)"
            >编辑</el-button>
            <el-button
              v-if="!row.builtin && session.can(API.ROLE_DELETE)"
              link
              type="danger"
              size="small"
              @click="remove(row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.editingId ? `编辑角色：${dialog.form.name || dialog.form.code}` : '新增角色'"
      width="720px"
      destroy-on-close
    >
      <el-form :model="dialog.form" label-width="92px">
        <el-form-item label="code" required>
          <el-input
            v-model="dialog.form.code"
            :disabled="dialog.editingId != null"
            placeholder="如 QA / PM（创建后不可修改）"
          />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="dialog.form.name" placeholder="角色显示名" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="dialog.form.description" type="textarea" :rows="2" />
        </el-form-item>

        <el-divider content-position="left">功能权限</el-divider>
        <div v-loading="dialog.metaLoading" class="perm-groups">
          <div v-for="group in permissionGroups" :key="group.module" class="perm-group">
            <div class="perm-group-head">{{ group.module }}</div>
            <!-- all_users 权限点：所有登录用户默认拥有，不写角色授权行 -->
            <div v-for="p in group.allUsers" :key="p.id" class="perm-item">
              <el-checkbox :model-value="true" disabled>{{ p.name }}</el-checkbox>
              <el-tag effect="light" type="info" size="small" class="perm-flag">所有登录用户</el-tag>
            </div>
            <el-checkbox-group v-model="dialog.form.permissionIds" class="perm-checkboxes">
              <el-checkbox v-for="p in group.items" :key="p.id" :value="p.id">
                {{ p.name }}
                <el-tag v-if="p.meta" effect="light" type="danger" size="small" class="perm-flag">元权限</el-tag>
              </el-checkbox>
            </el-checkbox-group>
          </div>
          <el-empty v-if="!permissionGroups.length && !dialog.metaLoading" description="暂无权限点" :image-size="48" />
        </div>

        <el-divider content-position="left">数据范围（可访问的实例）</el-divider>
        <el-form-item label="范围">
          <el-radio-group v-model="dialog.form.instanceScope">
            <el-radio value="ALL">全部实例</el-radio>
            <el-radio value="SELECTED">指定实例</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="dialog.form.instanceScope === 'SELECTED'" label="实例">
          <el-select
            v-model="dialog.form.instanceIds"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择该角色可访问的实例"
            style="width: 100%"
          >
            <el-option v-for="ins in instances" :key="ins.id" :label="ins.name" :value="ins.id">
              <span>{{ ins.name }}</span>
              <span class="option-meta">
                <DbBadge :dialect="ins.dialect" />
                <span>· {{ ins.host }}:{{ ins.port }}</span>
              </span>
            </el-option>
          </el-select>
          <div class="form-tip">
            留空表示该角色不能访问任何实例（提交变更 / 查询 / 浏览库表都会被拒）
          </div>
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
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { API } from '../../api/capabilities'
import { scopeMeta } from '../../constants'
import { useSessionStore } from '../../stores/session'
import DbBadge from '../../components/DbBadge.vue'

const session = useSessionStore()

const roles = ref([])
const permissions = ref([])
const instances = ref([])
const loading = ref(false)

/** 权限点按 module 分组（保持后端 sort 顺序）；all_users 项单列，不进勾选集合 */
const permissionGroups = computed(() => {
  const groups = []
  const index = new Map()
  for (const p of permissions.value) {
    const key = p.module || '其他'
    if (!index.has(key)) {
      index.set(key, { module: key, items: [], allUsers: [] })
      groups.push(index.get(key))
    }
    if (p.allUsers) index.get(key).allUsers.push(p)
    else index.get(key).items.push(p)
  }
  return groups
})

const instanceNameById = computed(
  () => new Map(instances.value.map((i) => [i.id, i.name]))
)

function instanceNames(ids) {
  if (!Array.isArray(ids) || !ids.length) return '（未授权任何实例）'
  return ids.map((id) => instanceNameById.value.get(id) || `实例 ${id}`).join('、')
}

async function load() {
  loading.value = true
  try {
    roles.value = (await api.listRoles()) || []
  } finally {
    loading.value = false
  }
}

/** 权限点目录与实例清单（编辑弹窗的选项来源，页面加载时取一次） */
async function loadMeta() {
  const [perms, insts] = await Promise.all([
    api.listPermissions().catch(() => []),
    api.listInstances().catch(() => [])
  ])
  permissions.value = perms || []
  instances.value = insts || []
}

onMounted(() => {
  load()
  loadMeta()
})

const dialog = reactive({
  visible: false,
  editingId: null,
  builtin: false,
  saving: false,
  metaLoading: false,
  form: { code: '', name: '', description: '', instanceScope: 'ALL', permissionIds: [], instanceIds: [] }
})

/** all_users 权限点不参与角色授权（后端按目录标记兜底），编辑与提交都过滤掉 */
const allUsersIds = computed(
  () => new Set(permissions.value.filter((p) => p.allUsers).map((p) => p.id))
)

async function openDialog(row = null) {
  dialog.editingId = row ? row.id : null
  dialog.builtin = !!row?.builtin
  Object.assign(dialog.form, {
    code: row?.code || '',
    name: row?.name || '',
    description: row?.description || '',
    instanceScope: row?.instanceScope || 'ALL',
    permissionIds: (row?.permissionIds || []).filter((id) => !allUsersIds.value.has(id)),
    instanceIds: [...(row?.instanceIds || [])]
  })
  dialog.visible = true
  // 目录/实例清单若首次加载失败，开弹窗时补拉一次
  if (!permissions.value.length || !instances.value.length) {
    dialog.metaLoading = true
    try {
      await loadMeta()
    } finally {
      dialog.metaLoading = false
    }
  }
}

async function save() {
  if (!dialog.form.code.trim()) {
    ElMessage.warning('请填写角色 code')
    return
  }
  if (!dialog.form.name.trim()) {
    ElMessage.warning('请填写角色名称')
    return
  }
  const permissionIds = dialog.form.permissionIds.filter((id) => !allUsersIds.value.has(id))
  const instanceIds = dialog.form.instanceScope === 'SELECTED' ? dialog.form.instanceIds : []
  dialog.saving = true
  try {
    if (dialog.editingId) {
      // code 不随编辑提交（内置角色 code 不可改，自定义角色亦按创建值固定）
      await api.updateRole(dialog.editingId, {
        name: dialog.form.name.trim(),
        description: dialog.form.description,
        instanceScope: dialog.form.instanceScope,
        permissionIds,
        instanceIds
      })
      ElMessage.success('角色已更新')
    } else {
      await api.createRole({
        code: dialog.form.code.trim(),
        name: dialog.form.name.trim(),
        description: dialog.form.description,
        instanceScope: dialog.form.instanceScope,
        permissionIds,
        instanceIds
      })
      ElMessage.success('角色已创建')
    }
    dialog.visible = false
    await load()
    // 改自己的角色权限后能力集合随之变化，立即刷新 /auth/me
    await session.fetchMe().catch(() => {})
  } catch {
    // 拦截器已提示错误（含 RBAC_LOCKOUT 防自锁 409）
  } finally {
    dialog.saving = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除角色「${row.name}」？仍持有该角色的用户与权限、实例授权将一并清理。`,
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
    // 拦截器已提示错误（内置角色 400 / 防自锁 409）
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
.scope-detail { margin-left: 8px; }

/* ---- 功能权限勾选（按 module 分组）---- */
.perm-groups { max-height: 340px; overflow-y: auto; padding: 0 4px 4px; }
.perm-group + .perm-group { margin-top: 14px; }
.perm-group-head {
  font-size: 11px; font-weight: 700; color: var(--sa-text-3);
  letter-spacing: 0.8px; margin-bottom: 6px;
}
.perm-checkboxes { display: flex; flex-wrap: wrap; gap: 4px 18px; }
.perm-item { display: flex; align-items: center; gap: 6px; }
.perm-flag { margin-left: 6px; }

.option-meta { float: right; font-size: 11px; color: var(--sa-text-3); margin-left: 12px; }
.form-tip { font-size: 11.5px; color: var(--sa-text-3); line-height: 1.5; margin-top: 4px; }
</style>
