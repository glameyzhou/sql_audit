<template>
  <div class="config-page">
    <div class="page-head">
      <div>
        <div class="page-title">权限配置</div>
        <div class="page-desc">角色 ↔ 权限点映射 · 界面配置优先、代码兜底（元权限不可修改）</div>
      </div>
      <span class="role-tag">当前角色：{{ roleLabel }} · 仅管理员可访问</span>
    </div>

    <el-card class="pane-card" shadow="never">
      <div class="pane-toolbar">
        <span class="toolbar-desc">勾选「角色」授予对应权限点；未配置项以代码兜底默认值为准</span>
        <el-button
          type="primary"
          size="small"
          class="toolbar-action"
          :loading="saving"
          @click="save"
        >保存配置</el-button>
      </div>

      <el-table v-loading="loading" :data="permissions" class="pane-table" empty-text="暂无权限点">
        <el-table-column label="权限点" min-width="240">
          <template #default="{ row }">
            <div class="perm-name">{{ row.name }}</div>
            <div class="perm-code mono">{{ row.code }}</div>
            <div class="perm-desc">{{ row.description || '-' }}</div>
          </template>
        </el-table-column>

        <el-table-column
          v-for="r in roles"
          :key="r.code"
          :label="r.name"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            <el-checkbox
              :model-value="hasRole(row, r.code)"
              :disabled="!row.configurable"
              @change="(v) => setRole(row, r.code, v)"
            />
          </template>
        </el-table-column>

        <el-table-column width="100" align="center">
          <template #header>
            <el-tooltip placement="top" :show-after="150" effect="light">
              <template #content>
                <div class="status-legend">
                  <div><b>元权限</b>：不可配置，硬编码 ADMIN（防自锁）</div>
                  <div><b>已配置</b>：界面保存过的角色覆盖，可为空 = 关闭</div>
                  <div><b>默认</b>：未配置，用代码兜底默认角色</div>
                </div>
              </template>
              <span class="col-head">状态<el-icon class="head-hint"><QuestionFilled /></el-icon></span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <el-tag v-if="!row.configurable" effect="light" type="info" size="small">元权限</el-tag>
            <el-tag v-else-if="row.customized" effect="light" type="warning" size="small">已配置</el-tag>
            <el-tag v-else effect="light" type="info" size="small">默认</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { api } from '../../api'
import { ROLE_LABELS } from '../../constants'
import { useSessionStore } from '../../stores/session'

const session = useSessionStore()

const roleLabel = computed(
  () => session.roles.map((r) => ROLE_LABELS[r] || r).join('、') || '未知角色'
)

const permissions = ref([])
const roles = ref([])
const loading = ref(false)
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    permissions.value = await api.listPermissions()
    roles.value = await api.listRoles()
  } finally {
    loading.value = false
  }
}

onMounted(load)

function hasRole(row, code) {
  return (row.roles || []).includes(code)
}

function setRole(row, code, checked) {
  if (!row.configurable) return
  if (!row.roles) row.roles = []
  if (checked && !row.roles.includes(code)) {
    row.roles.push(code)
  } else if (!checked) {
    row.roles = row.roles.filter((c) => c !== code)
  }
}

async function save() {
  const items = permissions.value
    .filter((p) => p.configurable)
    .map((p) => ({ code: p.code, roles: p.roles || [] }))
  try {
    await ElMessageBox.confirm(
      '将覆盖现有角色-权限点映射（降权即时生效，被移除的角色将立即失去对应访问权限），确认保存？',
      '保存权限配置',
      { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  saving.value = true
  try {
    await api.savePermissions(items)
    ElMessage.success('权限配置已保存')
    await load()
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    saving.value = false
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

.perm-name { font-size: 13px; color: var(--sa-text); font-weight: 600; }
.perm-code { font-size: 11px; color: var(--sa-brand); margin-top: 1px; }
.perm-desc { font-size: 12px; color: var(--sa-text-3); margin-top: 2px; }

.col-head { display: inline-flex; align-items: center; gap: 4px; cursor: help; }
.head-hint { color: var(--sa-text-3); font-size: 12px; }
/* effect="light"：白底浅边框气泡，与浅色主题一致，文字走主题深色 */
.status-legend { line-height: 1.9; font-size: 12px; }
</style>
