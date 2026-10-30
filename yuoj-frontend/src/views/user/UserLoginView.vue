<template>
  <section class="panel auth-card">
    <h1>欢迎回来</h1>
    <p class="intro">登录{{ brand.name }}，继续你的下一道挑战。</p>
    <a-form :model="form" layout="vertical" :rules="rules" @submit="handleSubmit">
      <a-form-item field="userAccount" label="账号"
        ><a-input v-model="form.userAccount" placeholder="请输入账号" autocomplete="username"
      /></a-form-item>
      <a-form-item field="userPassword" label="密码"
        ><a-input-password v-model="form.userPassword" placeholder="请输入密码" autocomplete="current-password"
      /></a-form-item>
      <a-button long type="primary" html-type="submit" :loading="submitting">登录</a-button>
    </a-form>
    <router-link class="auth-switch" :to="{ path: '/user/register', query: route.query }"
      >还没有账号？创建账号 →</router-link
    >
  </section>
</template>
<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useStore } from "vuex";
import message from "@arco-design/web-vue/es/message";
import { UserControllerService } from "../../../generated";
import { brand } from "@/config/brand";
import { safeReturnPath } from "@/utils/navigation";
const route = useRoute();
const router = useRouter();
const store = useStore();
const submitting = ref(false);
const form = reactive({
  userAccount: typeof route.query.account === "string" ? route.query.account : "",
  userPassword: ""
});
const rules = {
  userAccount: [{ required: true, message: "请输入账号" }],
  userPassword: [{ required: true, message: "请输入密码" }]
};
const handleSubmit = async ({ errors }) => {
  if (errors || submitting.value) return;
  submitting.value = true;
  try {
    const res = await UserControllerService.userLoginUsingPost(form);
    if (res.code !== 0) throw new Error(res.message || "登录失败");
    await store.dispatch("user/getLoginUser");
    if (!store.state.user.loginUser?.id) throw new Error("登录状态获取失败，请重试");
    message.success("登录成功");
    await router.replace(safeReturnPath(route.query.redirect));
  } catch (e) {
    message.error(e instanceof Error ? e.message : "网络异常，请稍后重试");
  } finally {
    submitting.value = false;
  }
};
</script>
