# SQL 审核平台 · 前端视觉风格规范（定版）

> 视觉基准：`sql-audit-ui-prototype.html`（light 主题为准）。
> Token 实现：`src/assets/styles/tokens.css`（`--sa-*` 平台 token + `--el-*` Element Plus 映射，EP 组件自动跟随主题）。
> 基调：内部工具，克制、信息密度友好，**默认浅色主题；禁止深色主题（W2 后再做）**。

## 1. 设计 Token

### 品牌色

| Token | 值 | 用途 |
| --- | --- | --- |
| `--sa-brand` | `#17315E` | 主品牌·深蓝：主按钮（`type="primary"`）、品牌区渐变起点 |
| `--sa-brand-2` | `#0FA8B7` | 交互强调·青：链接、激活态、focus ring、徽标对勾、进度条 |
| `--sa-brand-soft` | `#E6F6F8` | 品牌浅底：激活背景、focus ring、`b-brand` 徽章底 |

注：EP 的 `--el-color-primary-light-3`（hover 色）被映射为 `--sa-brand-2`——主按钮 hover 由深蓝变青，对齐原型 `.btn-primary:hover`。

### 功能色（soft 为同色浅底，用于徽章/notice）

| 语义 | Token | 值 | soft 值 |
| --- | --- | --- | --- |
| 成功 | `--sa-success` | `#15A06A` | `#E6F6EE` |
| 警告 | `--sa-warning` | `#D9860B` | `#FDF3E0` |
| 危险 | `--sa-danger` | `#DC3C47` | `#FDECEE` |
| 信息 | `--sa-info` | `#3568D4` | `#EAF1FD` |
| 紫色 | `--sa-purple` | `#7651C8` | `#F0EBFB`（影子表/快照/待执行类） |

### 中性色阶

| Token | 值 | 用途 |
| --- | --- | --- |
| `--sa-bg` | `#F4F6FA` | 页面背景 |
| `--sa-surface` | `#FFFFFF` | 卡片 / 面板 / 侧栏 |
| `--sa-surface-2` | `#F8FAFD` | 表头、次级底 |
| `--sa-surface-3` | `#EEF2F8` | hover 底、分段控件底 |
| `--sa-border` | `#E2E8F2` | 常规分割线 |
| `--sa-border-2` | `#CBD5E5` | 输入框 / 按钮描边 |
| `--sa-text` / `--sa-text-2` / `--sa-text-3` | `#14213D` / `#5A6B88` / `#8A99B2` | 主 / 次 / 弱文本 |

### 圆角 / 阴影 / 字体

| Token | 值 | 用途 |
| --- | --- | --- |
| `--sa-radius` | `10px` | 卡片、弹窗主体 |
| `--sa-radius-sm` | `7px` | 按钮、输入框、菜单项 |
| `--sa-shadow` | `0 1px 2px …, 0 8px 24px …` | 卡片 |
| `--sa-shadow-lg` | `0 12px 48px rgba(20,33,61,.16)` | 弹窗 / 抽屉 |
| `--sa-font-sans` | 系统栈 + PingFang SC / Microsoft YaHei | 全局正文，13px / 1.55 |
| `--sa-font-mono` | ui-monospace / SFMono / Menlo / Consolas | SQL 语句、工单号（TK-xxxx）、trace id |

## 2. Logo 使用规范

文件：`src/assets/logo.svg`（组件内 `import logoUrl from '@/assets/logo.svg'`）；favicon 同款位于 `public/favicon.svg`。

- **图形**：渐变圆角方块（135°，`#17315E → #0FA8B7`）+ 白色数据库圆柱 + 右下角白色对勾徽章（对勾为交互青 `#0FA8B7`）。数据库 = 数据资产，对勾 = 审核通过，渐变 = 极星动力品牌底色。
- **最小尺寸**：24×24px（含徽章，仍可辨识）；默认使用 30×30px（侧栏品牌区）、44×44px（登录页）。
- **留白**：logo 四周至少保留图形宽度 25% 的净空（30px logo → 四周 ≥8px）；禁止在 logo 上叠放文字或控件。
- **禁止**：拉伸变形、改色（渐变两端的品牌色不可替换）、加投影以外的滤镜、置于非纯色/高对比背景上。
- **配套排版**：品牌区文案「SQL 审核平台」（13.5px / 700）+ 副标「POLARSTARX · INTERNAL」（10.5px / `--sa-text-3` / letter-spacing 0.5px）。

## 3. 组件风格约定

### 按钮（Element Plus）

| 原型样式 | EP 用法 | 说明 |
| --- | --- | --- |
| `btn-primary` | `<el-button type="primary">` | 深蓝 `--sa-brand`，hover 变青 |
| `btn-accent` | `<el-button type="primary" plain>` 或自定义 | 青底强调（「解析并预审」类动作） |
| `btn` | `<el-button>` 默认 | 白底描边，hover 描边/文字变青 |
| `btn-danger` | `<el-button type="danger">` | 危险操作（驳回 / 删除） |
| `btn-ghost` | `<el-button text>` | 幽灵按钮（转交 / 取消） |
| 尺寸 | 默认 32px 高；`size="small"` 26px | 圆角 `--sa-radius-sm` |

### 表格

- 表头：`--sa-surface-2` 底、10.5px、`--sa-text-3`、letter-spacing 0.5px；行 hover `--sa-surface-2`。
- 等宽列（工单号 / 规则码 / SQL）一律加 `.mono` 类。
- 行内双行排版：主行 600 加粗 + 副行 10.5px 弱文本。

### 状态标签（软底色徽章，对齐原型 `.badge`）

统一形态：`soft 底色 + 同色深文字`，圆角 5px、10.5px、700。EP 中用 `<el-tag effect="light" round={false}>` 或自定义 `sa-badge` 类。

**工单状态 → 用色映射（对齐后端 TicketStatus 14 态）：**

| 状态 | 中文 | 用色 | EP tag type |
| --- | --- | --- | --- |
| `draft` | 草稿 | neutral（`--sa-surface-3` 底 + `--sa-text-2`） | `info` |
| `submitted` | 已提交 | neutral | `info` |
| `auditing` | 审核中 | info | `info` |
| `pending_approval` | 待审批 | warning | `warning` |
| `approved` | 已批准 | brand（`--sa-brand-soft` + `--sa-brand-2`） | `primary` |
| `rejected` | 已驳回 | danger | `danger` |
| `scheduled` | 待执行 | purple | 自定义 |
| `executing` | 执行中 | info | `info` |
| `succeeded` | 已成功 | success | `success` |
| `partially_succeeded` | 部分成功 | warning | `warning` |
| `needs_confirm` | 待人工确认 | danger | `danger` |
| `failed` | 失败 | danger | `danger` |
| `closed` | 已关闭 | neutral | `info` |
| `expired` | 已过期 | neutral | `info` |

**风险等级映射：** `LOW`=success、`MEDIUM`=info、`HIGH`=warning、`CRITICAL`=danger（CRITICAL 必须醒目，不可降级显示）。

**回滚能力映射：** `FULL`=success、`PARTIAL`=warning、`NONE`=danger、`SHADOW`/`SNAPSHOT`=purple。

### 其他约定

- **侧栏**：白底 228px，可折叠至 64px；菜单项 36px 高、`--sa-radius-sm` 圆角；hover `--sa-surface-3`；激活 `--sa-brand-soft` 底 + `--sa-brand` 字 + 左侧 3px 青色指示条；分组标题 10px/600/大写字距。
- **顶栏**：56px 白底；面包屑「极星动力 / 页面名」12px。
- **卡片**：`--sa-surface` 底 + `--sa-border` 边 + `--sa-radius` 圆角 + `--sa-shadow` 阴影；卡片头 13px/650 带底部分割线。
- **弹窗**：圆角 14px + `--sa-shadow-lg`；抽屉从右滑入（宽 ≤600px）。
- **notice 提示条**：info/warn/danger/ok 四态 soft 底 + 同色文字，11.5px。
- **数字统计**：24px/700 + `font-variant-numeric: tabular-nums`。
- **遮罩**：`rgba(10,18,35,.42)` + 2px backdrop blur。

## 4. 文件与引用关系

```
frontend/
├── index.html                     # favicon → /favicon.svg
├── public/favicon.svg             # 浏览器标签图标（与 logo 同款）
├── src/
│   ├── assets/
│   │   ├── logo.svg               # 品牌图形（Layout / Login 引用）
│   │   └── styles/
│   │       ├── tokens.css         # 设计 token + EP 主题映射
│   │       └── main.css           # @import tokens.css；全局字体/背景/滚动条
│   └── views/
│       ├── Layout.vue             # 品牌区 + 分组导航（@element-plus/icons-vue）
│       └── Login.vue              # 居中卡片 + logo + 产品名
```

加载顺序（勿调整）：`element-plus/dist/index.css` → `main.css`（内含 `@import './tokens.css'`），保证 `--el-*` 映射覆盖 EP 默认主题。
