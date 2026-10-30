<template>
  <section class="panel auth-card">
    <h1>开启你的代码之旅</h1>
    <p class="intro">加入{{ brand.name }}，让每一次练习都有收获。</p>
    <a-form :model="form" layout="vertical" :rules="rules" @submit="handleSubmit">
      <a-form-item field="userAccount" label="账号"
        ><a-input v-model="form.userAccount" placeholder="至少 4 位" autocomplete="username"
      /></a-form-item>
      <a-form-item field="userPassword" label="密码"
        ><a-input-password v-model="form.userPassword" placeholder="至少 8 位" autocomplete="new-password"
      /></a-form-item>
      <a-form-item field="checkPassword" label="确认密码"
        ><a-input-password v-model="form.checkPassword" placeholder="再次输入密码" autocomplete="new-password"
      /></a-form-item>
      <a-button long type="primary" html-type="submit" :loading="submitting">创建账号</a-button>
    </a-form>
    <router-link class="auth-switch" :to="{ path: '/user/login', query: route.query }">已有账号？去登录 →</router-link>
  </section>
</template>
<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import message from "@arco-design/web-vue/es/message";
import { UserControllerService } from "../../../generated";
import { brand } from "@/config/brand";
const router = useRouter();
const route = useRoute();
const submitting = ref(false);
const form = reactive({ userAccount: "", userPassword: "", checkPassword: "" });
const rules = {
  userAccount: [
    { required: true, message: "请输入账号" },
    { minLength: 4, message: "账号至少需要 4 位" }
  ],
  userPassword: [
    { required: true, message: "请输入密码" },
    { minLength: 8, message: "密码至少需要 8 位" }
  ],
  checkPassword: [
    { required: true, message: "请再次输入密码" },
    { validator: (value, callback) => callback(value === form.userPassword ? undefined : "两次输入的密码不一致") }
  ]
};
const handleSubmit = async ({ errors }) => {
  if (errors || submitting.value) return;
  submitting.value = true;
  try {
    const res = await UserControllerService.userRegisterUsingPost(form);
    if (res.code !== 0) throw new Error(res.message || "注册失败");
    message.success("注册成功，请登录");
    await router.replace({ path: "/user/login", query: { ...route.query, account: form.userAccount } });
  } catch (e) {
    message.error(e instanceof Error ? e.message : "注册请求失败，请稍后重试");
  } finally {
    submitting.value = false;
  }
};
</script>
