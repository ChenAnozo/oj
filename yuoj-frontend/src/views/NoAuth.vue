<template>
  <div class="panel">
    <a-result status="403" title="暂时无法访问" subtitle="当前账号没有访问此页面的权限。"
      ><template #extra
        ><a-button type="primary" @click="router.push('/questions')">返回题库</a-button></template
      ></a-result
    >
  </div>
</template>
<script setup lang="ts">
import { useRouter } from "vue-router";
const router = useRouter();
</script>
