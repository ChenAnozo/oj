<template>
  <div class="auth-layout">
    <header><BrandLogo /><router-link to="/questions">返回题库 →</router-link></header>
    <main>
      <div class="auth-message">
        <div class="eyebrow">THINK. CODE. GROW.</div>
        <h2>把思考，<br />写成下一次进步。</h2>
        <p>{{ brand.tagline }}</p>
        <div class="code-art" aria-hidden="true">
          <span>01</span> while (curious) &#123;<br /><span>02</span> &nbsp; think();<br /><span>03</span> &nbsp;
          code();<br /><span>04</span> &nbsp; grow();<br /><span>05</span> &#125;
        </div>
      </div>
      <div class="auth-form"><router-view /></div>
    </main>
    <footer>{{ brand.name }} · 算法练习平台</footer>
  </div>
</template>
<script setup lang="ts">
import BrandLogo from "@/components/BrandLogo.vue";
import { brand } from "@/config/brand";
</script>
<style scoped>
.auth-layout {
  min-height: 100vh;
  background: radial-gradient(ellipse at 15% 45%, #e8edff, transparent 65%), #f7f9fd;
  display: flex;
  flex-direction: column;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28px 5%;
}
header > a {
  font-size: 13px;
}
main {
  width: 100%;
  max-width: 1100px;
  padding: 46px 30px;
  margin: auto;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 80px;
  align-items: center;
}
h2 {
  font-size: 42px;
  line-height: 1.5;
  margin: 18px 0;
  letter-spacing: -1px;
}
p {
  color: #778197;
}
.code-art {
  font: 16px/2.2 Consolas, monospace;
  color: #4658d6;
  padding: 24px 0;
}
.code-art span {
  color: #a8b0c4;
  margin-right: 22px;
}
.auth-form {
  min-width: 0;
}
footer {
  text-align: center;
  padding: 24px;
  font-size: 12px;
  color: #8891a3;
}
@media (max-width: 800px) {
  main {
    display: block;
    padding: 24px 16px;
  }
  .auth-message {
    display: none;
  }
}
</style>
