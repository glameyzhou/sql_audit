<template>
  <div class="config-page">
    <div class="page-head">
      <div>
        <div class="page-title">权限目录</div>
        <div class="page-desc">权限点 → 绑定接口（方法 + 路径模式）· 只读，目录与绑定随版本发布维护</div>
      </div>
      <span class="role-tag">只读 · 角色授权请前往「角色管理」</span>
    </div>

    <el-card class="pane-card" shadow="never">
      <div class="pane-toolbar">
        <span class="toolbar-desc">
          后端按「HTTP 方法 + 处理器模式」精确查表鉴权；同一接口可绑多个权限点，持有任一即放行
        </span>
        <el-button size="small" :loading="loading" class="toolbar-action" @click="load">刷新</el-button>
      </div>

      <div v-loading="loading" class="catalog">
        <div v-for="group in groups" :key="group.module" class="module-block">
          <div class="module-head">
            <span class="module-name">{{ group.module }}</span>
            <span class="module-count">{{ group.items.length }} 个权限点</span>
          </div>

          <div v-for="p in group.items" :key="p.id" class="perm-card">
            <div class="perm-main">
              <div class="perm-title">
                <span class="perm-name">{{ p.name }}</span>
                <span class="perm-code mono">{{ p.code }}</span>
                <el-tag v-if="p.allUsers" effect="light" type="info" size="small">所有登录用户</el-tag>
                <el-tag v-if="p.meta" effect="light" type="danger" size="small">元权限</el-tag>
              </div>
              <div class="perm-desc">{{ p.description || '—' }}</div>
            </div>

            <div class="perm-side">
              <div class="side-row">
                <span class="side-k">绑定接口</span>
                <div class="api-list">
                  <span v-for="a in p.apis" :key="a.method + a.pattern" class="api-chip mono">
                    <b>{{ a.method }}</b> {{ a.pattern }}
                  </span>
                  <span v-if="!p.apis || !p.apis.length" class="side-empty">未绑定接口</span>
                </div>
              </div>
              <div class="side-row">
                <span class="side-k">持有角色</span>
                <div class="role-list">
                  <el-tag
                    v-for="r in p.roles"
                    :key="r.id ?? r.code"
                    effect="light"
                    size="small"
                    class="role-chip"
                  >{{ r.name || r.code }}</el-tag>
                  <span v-if="p.allUsers" class="side-empty">全部（无需授权）</span>
                  <span v-else-if="!p.roles || !p.roles.length" class="side-empty">暂无角色持有</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <el-empty v-if="!loading && !groups.length" description="暂无权限点" :image-size="80" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../../api'

const permissions = ref([])
const loading = ref(false)

/** 按 module 分组（保持后端 sort 顺序） */
const groups = computed(() => {
  const list = []
  const index = new Map()
  for (const p of permissions.value) {
    const key = p.module || '其他'
    if (!index.has(key)) {
      index.set(key, { module: key, items: [] })
      list.push(index.get(key))
    }
    index.get(key).items.push(p)
  }
  return list
})

async function load() {
  loading.value = true
  try {
    permissions.value = (await api.listPermissions()) || []
  } catch {
    // 拦截器已提示错误
  } finally {
    loading.value = false
  }
}

onMounted(load)
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

.catalog { min-height: 120px; }
.module-block + .module-block { margin-top: 20px; }
.module-head {
  display: flex; align-items: baseline; gap: 10px;
  padding-bottom: 8px; margin-bottom: 10px;
  border-bottom: 1px solid var(--sa-border);
}
.module-name { font-size: 13px; font-weight: 650; color: var(--sa-text); }
.module-count { font-size: 11px; color: var(--sa-text-3); }

.perm-card {
  display: grid;
  grid-template-columns: minmax(200px, 300px) minmax(0, 1fr);
  gap: 18px;
  padding: 12px 14px;
  background: var(--sa-surface-2);
  border: 1px solid var(--sa-border);
  border-radius: var(--sa-radius-sm);
}
.perm-card + .perm-card { margin-top: 8px; }

.perm-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.perm-name { font-size: 13px; font-weight: 600; color: var(--sa-text); }
.perm-code { font-size: 11px; color: var(--sa-brand); }
.perm-desc { font-size: 12px; color: var(--sa-text-3); margin-top: 3px; line-height: 1.5; }

.perm-side { display: flex; flex-direction: column; gap: 8px; }
.side-row { display: flex; gap: 10px; }
.side-k { flex: none; width: 56px; font-size: 11px; color: var(--sa-text-3); padding-top: 3px; }
.side-empty { font-size: 11.5px; color: var(--sa-text-3); }

.api-list { display: flex; flex-wrap: wrap; gap: 6px; }
.api-chip {
  font-size: 11px;
  color: var(--sa-text-2);
  background: var(--sa-surface);
  border: 1px solid var(--sa-border);
  border-radius: 5px;
  padding: 1px 7px;
}
.api-chip b { color: var(--sa-brand-2); font-weight: 700; }

.role-list { display: flex; flex-wrap: wrap; gap: 4px; }
.role-chip { margin: 0; }

@media (max-width: 1100px) {
  .perm-card { grid-template-columns: minmax(0, 1fr); }
}
</style>
