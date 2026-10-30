import { createRouter, createWebHistory } from "vue-router";
import { brand } from "@/config/brand";
import { routes } from "@/router/routes";

const router = createRouter({
  history: createWebHistory(process.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 })
});

router.afterEach((to) => {
  document.title = `${String(to.name || "算法练习")} · ${brand.name} ${brand.englishName}`;
});

export default router;
