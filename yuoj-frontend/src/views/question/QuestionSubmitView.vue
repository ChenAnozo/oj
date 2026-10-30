<template>
  <section>
    <div class="page-heading">
      <div class="eyebrow">SUBMISSIONS</div>
      <h1>每次提交，都有回响。</h1>
      <p>跟踪判题进度，回顾每一次尝试。</p>
    </div>
    <div class="panel">
      <form class="filter-bar" @submit.prevent="search">
        <label class="filter-field"
          ><span>题号</span><a-input v-model="filters.questionId" placeholder="输入完整题号" allow-clear
        /></label>
        <label class="filter-field"
          ><span>语言</span
          ><a-select v-model="filters.language" placeholder="全部语言" allow-clear :options="languageOptions"
        /></label>
        <label class="filter-field"
          ><span>判题状态</span
          ><a-select v-model="filters.status" placeholder="全部状态" allow-clear
            ><a-option v-for="(text, value) in statusOptions" :key="value" :value="String(value)">{{
              text
            }}</a-option></a-select
          ></label
        >
        <a-space
          ><a-button html-type="submit" type="primary" :loading="loading">查询</a-button
          ><a-button @click="reset">重置</a-button><a-button :loading="loading" @click="load">刷新</a-button></a-space
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
          <template #question="{ record }"
            ><router-link :to="'/view/question/' + record.questionId">{{ record.questionId }} ↗</router-link></template
          >
          <template #status="{ record }"
            ><a-tag :color="STATUS_COLOR[record.status] || 'gray'">{{
              STATUS_DISPLAY[record.status] || "未知状态"
            }}</a-tag></template
          >
          <template #result="{ record }"
            ><template v-if="record.judgeInfo?.message"
              ><a-tag :color="getJudgeMessageColor(record.judgeInfo.message)">{{
                getJudgeMessageText(record.judgeInfo.message)
              }}</a-tag>
              <div class="muted" style="margin-top: 6px">
                {{ record.judgeInfo.time ?? "—" }} ms · {{ record.judgeInfo.memory ?? "—" }} KB
              </div></template
            ><span v-else class="muted">等待结果</span></template
          >
          <template #time="{ record }">{{
            record.createTime ? moment(record.createTime).format("YYYY-MM-DD HH:mm:ss") : "—"
          }}</template>
          <template #empty><div class="empty-state">暂时没有提交记录，去题库开启一次挑战吧。</div></template>
        </a-table>
      </div>
    </div>
  </section>
</template>
<script setup lang="ts">
import moment from "moment";
import { QuestionControllerService, QuestionSubmitVO } from "../../../generated";
import { usePagedList } from "@/composables/usePagedList";
import { languageOptions } from "@/config/brand";
import { STATUS_DISPLAY, STATUS_COLOR, getJudgeMessageText, getJudgeMessageColor } from "@/constants/judgeConstants";
const statusOptions = Object.fromEntries(Object.entries(STATUS_DISPLAY).filter(([key]) => Number(key) >= 0));
const { filters, rows, total, current, loading, error, load, search, reset, changePage } =
  usePagedList<QuestionSubmitVO>(["questionId", "language", "status"], (query) =>
    QuestionControllerService.listQuestionSubmitByPageUsingPost(query)
  );
const columns = [
  { title: "提交号", dataIndex: "id", width: 190 },
  { title: "题目", slotName: "question", width: 190 },
  { title: "语言", dataIndex: "language", width: 80 },
  { title: "状态", slotName: "status", width: 130 },
  { title: "判题结果", slotName: "result", width: 180 },
  { title: "提交时间", slotName: "time", width: 180 }
];
</script>
