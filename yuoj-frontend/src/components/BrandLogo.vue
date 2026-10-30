<template>
  <router-link to="/questions" class="brand" :aria-label="brand.name + '，返回题库'">
    <img src="@/assets/oj-logo.svg" alt="" width="38" height="38" />
    <span
      ><strong>{{ brand.name }}</strong
      ><small>{{ brand.englishName }}</small></span
    >
  </router-link>
</template>
<script setup lang="ts">
import { brand } from "@/config/brand";
</script>
