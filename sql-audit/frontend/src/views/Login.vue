<template>
  <div class="login-wrap">
    <!-- 背景装饰（品牌浅底 radial，克制） -->
    <div class="deco deco-lt" />
    <div class="deco deco-rb" />

    <div class="login-card">
      <img class="login-logo" :src="logoUrl" alt="logo" />
      <h1 class="login-title">SQL 审核平台</h1>
      <p class="login-sub">POLARSTARX · INTERNAL</p>

      <el-form label-position="top" class="login-form">
        <el-form-item label="用户名"><el-input placeholder="待 W2 接入" disabled /></el-form-item>
        <el-form-item label="密码"><el-input type="password" placeholder="待 W2 接入" disabled show-password /></el-form-item>
        <el-button type="primary" class="w-full" disabled>登录</el-button>
      </el-form>

      <p class="login-foot">极星动力 · 以星为引，每一步变更皆有迹可循</p>
    </div>
  </div>
</template>

<script setup>
import logoUrl from '../assets/logo.svg'
</script>

<style scoped>
.login-wrap {
  position: relative;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--sa-bg);
  overflow: hidden;
}

/* 品牌浅底装饰：两块 radial 渐变，置于卡片后方 */
.deco { position: absolute; border-radius: 50%; pointer-events: none; }
.deco-lt {
  width: 420px; height: 420px;
  left: -120px; top: -120px;
  background: radial-gradient(circle, rgba(15, 168, 183, 0.10) 0%, transparent 70%);
}
.deco-rb {
  width: 480px; height: 480px;
  right: -140px; bottom: -140px;
  background: radial-gradient(circle, rgba(23, 49, 94, 0.08) 0%, transparent 70%);
}

.login-card {
  position: relative;
  width: 380px;
  background: var(--sa-surface);
  border: 1px solid var(--sa-border);
  border-radius: 14px;
  box-shadow: var(--sa-shadow-lg);
  padding: 36px 32px 24px;
  text-align: center;
}

.login-logo { width: 44px; height: 44px; border-radius: 11px; box-shadow: 0 3px 10px rgba(15, 168, 183, 0.28); }
.login-title { margin-top: 14px; font-size: 19px; font-weight: 700; letter-spacing: -0.2px; color: var(--sa-text); }
.login-sub { margin-top: 4px; font-size: 10.5px; color: var(--sa-text-3); letter-spacing: 1px; }

.login-form { margin-top: 24px; text-align: left; }

.login-foot {
  margin-top: 20px;
  font-size: 10.5px;
  color: var(--sa-text-3);
}

.w-full { width: 100%; }
</style>
