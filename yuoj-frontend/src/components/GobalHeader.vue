<template>
  <header class="global-header">
    <BrandLogo />
    <nav aria-label="主导航">
      <router-link
        v-for="item in visibleRoutes"
        :key="item.path"
        :to="item.path"
        :class="{ active: activePath === item.path }"
        >{{ item.name }}</router-link
      >
    </nav>
    <div class="account">
      <template v-if="!isLoggedIn">
        <router-link :to="{ path: '/user/login', query: { redirect: route.fullPath } }">登录</router-link>
        <a-button type="primary" @click="router.push('/user/register')">注册</a-button>
      </template>
      <a-dropdown v-else @select="logout">
        <a-button>{{ store.state.user.loginUser.userName || "我的账号" }} ▾</a-button>
        <template #content><a-doption value="logout">退出登录</a-doption></template>
      </a-dropdown>
    </div>
  </header>
</template>
<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useStore } from "vuex";
import { routes } from "@/router/routes";
import checkAccess from "@/access/checkAccess";
import BrandLogo from "@/components/BrandLogo.vue";
import message from "@arco-design/web-vue/es/message";
const router = useRouter();
const route = useRoute();
const store = useStore();
const isLoggedIn = computed(() => !!store.state.user?.loginUser?.id);
const visibleRoutes = computed(() =>
  routes.filter(
    (item) => !item.meta?.hideInMenu && checkAccess(store.state.user.loginUser, item.meta?.access as string)
  )
);
const activePath = computed(() =>
  route.path.startsWith("/view/question/")
    ? "/questions"
    : route.path === "/update/question"
    ? "/manage/question/"
    : route.path
);
const logout = async () => {
  try {
    await store.dispatch("user/userLoginOut");
    await router.push("/questions");
    message.success("已退出登录");
  } catch {
    message.error("退出失败，请重试");
  }
};
</script>
<style scoped>
.global-header {
  min-height: 78px;
  max-width: 1480px;
  padding: 0 36px;
  margin: auto;
  display: flex;
  align-items: center;
  gap: 48px;
}
nav {
  display: flex;
  gap: 28px;
  align-self: stretch;
  align-items: center;
  flex: 1;
}
nav a {
  color: #737d92;
  font-size: 14px;
  height: 100%;
  display: flex;
  align-items: center;
  border-bottom: 3px solid transparent;
  padding-top: 3px;
  white-space: nowrap;
}
nav a.active {
  color: #4658d6;
  border-color: #4658d6;
  font-weight: 600;
}
.account {
  display: flex;
  align-items: center;
  gap: 18px;
}
@media (max-width: 850px) {
  .global-header {
    padding: 14px 18px 0;
    gap: 18px;
    flex-wrap: wrap;
  }
  nav {
    order: 3;
    flex-basis: 100%;
    overflow-x: auto;
    gap: 24px;
    height: 46px;
  }
  .account {
    margin-left: auto;
  }
}
</style>
