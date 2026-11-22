/**
 * 共享常量：状态 / 风险 / 回滚覆盖的中文与颜色映射（对齐后端枚举）。
 *
 * 后端存小写字符串（TicketStateMachine.dbValue），前端 key 用小写。
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

/** 环境 */
export const ENV = {
  prod: { label: '生产', type: 'danger' },
  staging: { label: '预发', type: 'warning' }
}

/**
 * 开发期当前用户占位（W2 接登录/JWT 后从 session store 取，替换此常量）。
 * 审批决策的 approverId 用 200（模拟主管，非本人可过防自审）。
 */
export const CURRENT_USER = { id: 100, name: '文曜', role: 'DEV' }

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
