<template>
  <section>
    <div class="page-heading">
      <div class="eyebrow">PROBLEM LIBRARY</div>
      <h1>从一道题，开始新的进步。</h1>
      <p>寻找值得思考的问题，把想法变成能运行的代码。</p>
    </div>
    <div class="panel">
      <form class="filter-bar" @submit.prevent="search">
        <label class="filter-field"
          ><span>题目名称</span><a-input v-model="filters.title" placeholder="搜索你想挑战的题目" allow-clear
        /></label>
        <label class="filter-field"
          ><span>知识标签</span><a-input v-model="filters.tags" placeholder="例如：数组，动态规划" allow-clear
        /></label>
        <a-space
          ><a-button type="primary" html-type="submit" :loading="loading">搜索题目</a-button
          ><a-button @click="reset">重置</a-button></a-space
        >
      </form>
      <a-alert v-if="error" type="error" style="margin-bottom: 16px"
        >{{ error }} <a-link @click="load">重试</a-link></a-alert
      >
      <div class="table-scroll">
        <a-table
          :columns="columns"
          :data="rows"
          :loading="loading"
          :pagination="{ current, pageSize: 10, total, showTotal: true }"
          @page-change="changePage"
        >
          <template #title="{ record }"
            ><router-link
              class="question-link"
              :to="{ path: '/view/question/' + record.id, query: { from: route.fullPath } }"
              >{{ record.title }}</router-link
            ></template
          >
          <template #tags="{ record }"
            ><a-space wrap
              ><a-tag v-for="tag in record.tags" :key="tag" color="arcoblue">{{ tag }}</a-tag></a-space
            ></template
          >
          <template #rate="{ record }"
            ><span class="rate"
              >{{ record.submitNum ? (((record.acceptedNum || 0) / record.submitNum) * 100).toFixed(1) : "0.0" }}%</span
            ><span class="muted"> · {{ record.acceptedNum || 0 }}/{{ record.submitNum || 0 }}</span></template
          >
          <template #action="{ record }"
            ><router-link :to="{ path: '/view/question/' + record.id, query: { from: route.fullPath } }"
              >开始挑战 →</router-link
            ></template
          >
          <template #empty><div class="empty-state">暂时没有匹配的题目，试试其他关键词或清空筛选。</div></template>
        </a-table>
      </div>
    </div>
  </section>
</template>
<script setup lang="ts">
import { useRoute } from "vue-router";
import { QuestionControllerService, QuestionVO } from "../../../generated";
import { usePagedList } from "@/composables/usePagedList";
const route = useRoute();
const { filters, rows, total, current, loading, error, load, search, reset, changePage } = usePagedList<QuestionVO>(
  ["title", "tags"],
  (query) => QuestionControllerService.listQuestionVoByPageUsingPost(query)
);
const columns = [
  { title: "题号", dataIndex: "id", width: 190 },
  { title: "题目", slotName: "title" },
  { title: "知识标签", slotName: "tags" },
  { title: "通过率", slotName: "rate", width: 170 },
  { title: "", slotName: "action", width: 130 }
];
</script>
<style scoped>
.question-link {
  font-weight: 600;
  color: #28324c;
}
.question-link:hover {
  color: #4658d6;
}
.rate {
  color: #29957d;
  font-variant-numeric: tabular-nums;
}
</style>
