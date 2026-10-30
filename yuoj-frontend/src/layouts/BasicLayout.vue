<template>
  <div class="site-layout">
    <div class="site-header"><GobalHeader /></div>
    <main><router-view /></main>
    <footer>{{ brand.name }} · {{ brand.englishName }}<span>保持思考，持续进步。</span></footer>
  </div>
</template>
<script setup lang="ts">
import GobalHeader from "@/components/GobalHeader.vue";
import { brand } from "@/config/brand";
</script>
<style scoped>
.site-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.site-header {
  background: white;
  border-bottom: 1px solid #e7ebf3;
}
main {
  width: 100%;
  max-width: 1480px;
  margin: 0 auto;
  padding: 38px 36px;
  flex: 1;
}
footer {
  max-width: 1408px;
  width: calc(100% - 72px);
  margin: 0 auto;
  border-top: 1px solid #e3e8f1;
  padding: 22px 0;
  color: #8891a3;
  font-size: 12px;
  display: flex;
  justify-content: space-between;
}
@media (max-width: 640px) {
  main {
    padding: 26px 16px;
  }
  footer {
    width: calc(100% - 32px);
    gap: 12px;
    flex-wrap: wrap;
  }
}
</style>
