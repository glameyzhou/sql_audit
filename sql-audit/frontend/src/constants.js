/**
 * 共享常量：状态 / 风险 / 回滚覆盖的中文与颜色映射（对齐后端枚举）。
 *
 * 后端 DB 存小写存储码（Java 侧是 TicketStatus 枚举，存储码见其 getCode），前端 key 用小写。
 * el-tag type 只支持 primary/success/info/warning/danger。
 */

/** 工单 14 态（sqlaudit-common TicketStatus） */
export const TICKET_STATUS = {
  draft: { label: '草稿', type: 'info' },
  submitted: { label: '已提交', type: 'info' },
  auditing: { label: '审核中', type: 'info' },
  approved: { label: '已批准', type: 'primary' },
  rejected: { label: '已驳回', type: 'danger' },
  scheduled: { label: '已排期', type: 'primary' },
  executing: { label: '执行中', type: 'info' },
  succeeded: { label: '执行成功', type: 'success' },
  partially_succeeded: { label: '部分成功', type: 'warning' },
  failed: { label: '执行失败', type: 'danger' },
  needs_confirm: { label: '待人工确认', type: 'danger' },
  closed: { label: '已关闭', type: 'info' },
  cancelled: { label: '已取消', type: 'info' },
  expired: { label: '已过期', type: 'info' }
}

/** 风险等级（RiskLevel） */
export const RISK_LEVEL = {
  LOW: { label: '低危', type: 'success' },
  MEDIUM: { label: '中危', type: 'info' },
  HIGH: { label: '高危', type: 'warning' },
  CRITICAL: { label: '严重', type: 'danger' }
}

/** 回滚覆盖（rollback_summary.coverage） */
export const ROLLBACK_COVERAGE = {
  FULL: { label: '可回滚', type: 'success' },
  PARTIAL: { label: '部分可回滚', type: 'warning' },
  NONE: { label: '不可回滚', type: 'danger' },
  SNAPSHOT: { label: '快照回滚', type: 'info' },
  SHADOW: { label: '影子表', type: 'info' }
}

/** 环境（三档：dev/test/prod；原「预发 staging」统一改名 test，旧数据兼容见各视图 envMeta） */
export const ENV = {
  dev: { label: '开发', type: 'info' },
  test: { label: '测试', type: 'warning' },
  prod: { label: '生产', type: 'danger' }
}

/** 角色中文映射（后端 user.roles 数组元素） */
export const ROLE_LABELS = {
  DEV: '开发',
  LEAD: '主管',
  DBA: 'DBA',
  ADMIN: '管理员'
}

/** session 持久化键（session store 写入，api 拦截器读取） */
export const STORAGE_KEYS = {
  token: 'sa_token',
  user: 'sa_user',
  env: 'sa_env'
}

/**
 * 环境元信息（带 legacy 兼容：历史数据 environment 可能为 'staging'，统一按「测试」展示）。
 * 三档之外的未知值返回兜底 info。
 */
export function envMeta(env) {
  if (env === 'staging') return ENV.test
  return ENV[env] || { label: env || '-', type: 'info' }
}

/** 状态标签的兜底：未知状态显示原文 */
export function statusMeta(status) {
  return TICKET_STATUS[status] || { label: status || '-', type: 'info' }
}

export function riskMeta(level) {
  return RISK_LEVEL[level] || { label: level || '-', type: 'info' }
}

export function coverageMeta(coverage) {
  return ROLLBACK_COVERAGE[coverage] || { label: coverage || '-', type: 'info' }
}
