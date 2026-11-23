/**
 * 共享常量：状态 / 风险 / 回滚覆盖 / RBAC 的中文与颜色映射（对齐后端枚举）。
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

/**
 * 工单终局状态（不可取消）。
 * 对齐后端 TicketStatus：isTerminal() 覆盖 closed / cancelled / expired；
 * succeeded / partially_succeeded / failed 亦只能走向 closed（状态机
 * canTransitionTo(CANCELLED) 一律为 false），与终态同样不可取消。
 * executing / needs_confirm 虽非严格终态，但状态机 canTransitionTo(CANCELLED)
 * 同样为 false（后端拒绝取消）——此处镜像该行为隐藏取消按钮，后端始终是唯一权威。
 */
export const TERMINAL_TICKET_STATUSES = [
  'succeeded',
  'partially_succeeded',
  'failed',
  'executing',
  'needs_confirm',
  'closed',
  'cancelled',
  'expired'
]

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

/** 角色数据范围（InstanceScope，角色级实例授权） */
export const INSTANCE_SCOPE = {
  ALL: { label: '全部实例', type: 'success' },
  SELECTED: { label: '指定实例', type: 'warning' }
}

/** 审批节点审批人类型（ApproverType） */
export const APPROVER_TYPE = {
  USER: '指定人',
  ROLE: '角色',
  INSTANCE_OWNER: '实例负责人'
}

/** 审批组签署模式（ApprovalMode） */
export const APPROVAL_MODE = {
  AND: '会签（全部通过）',
  OR: '或签（任一通过）'
}

/** 审批记录结果（ApprovalResult） */
export const APPROVAL_RESULT = {
  PENDING: { label: '待审批', type: 'warning' },
  APPROVED: { label: '已通过', type: 'success' },
  REJECTED: { label: '已驳回', type: 'danger' },
  TRANSFERRED: { label: '已转交', type: 'info' },
  SKIPPED: { label: '已跳过', type: 'info' }
}

/** session 持久化键（session store 写入，api 拦截器读取） */
export const STORAGE_KEYS = {
  token: 'sa_token',
  user: 'sa_user'
}

export function scopeMeta(scope) {
  return INSTANCE_SCOPE[scope] || { label: scope || '-', type: 'info' }
}

export function approverTypeLabel(type) {
  return APPROVER_TYPE[type] || type || '-'
}

export function approvalModeLabel(mode) {
  return APPROVAL_MODE[mode] || mode || '-'
}

export function approvalResultMeta(result) {
  return APPROVAL_RESULT[result] || { label: result || '-', type: 'info' }
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
