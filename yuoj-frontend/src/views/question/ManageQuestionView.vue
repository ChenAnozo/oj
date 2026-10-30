<template>
  <div id="manageQuestionView">
    <a-form :model="searchParams" layout="inline">
      <a-form-item field="title" label="标题" style="min-width: 200px">
        <a-input v-model="searchParams.title" placeholder="请输入标题" allow-clear />
      </a-form-item>
      <a-form-item field="tags" label="标签" style="min-width: 240px">
        <a-input-tag v-model="searchParams.tags" placeholder="请输入标签" allow-clear />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" @click="doSearch">搜索</a-button>
      </a-form-item>
      <a-form-item>
        <a-button type="outline" status="success" @click="doAdd">新增题目</a-button>
      </a-form-item>
    </a-form>
    <a-divider size="0" />
    <a-table
      :loading="loading"
      :columns="columns"
      :data="dataList"
      :bordered="{ wrapper: true, cell: true }"
      :stripe="true"
      :pagination="{
        showTotal: true,
        pageSize: searchParams.pageSize,
        current: searchParams.current,
        total
      }"
      @page-change="onPageChange"
    >
      <template #tags="{ record }">
        <a-space wrap>
          <a-tag v-for="(tag, index) of JSON.parse(record.tags || '[]')" :key="index" color="arcoblue">{{ tag }}</a-tag>
        </a-space>
      </template>
      <template #createTime="{ record }">
        {{ moment(record.createTime).format("YYYY-MM-DD") }}
      </template>
      <template #optional="{ record }">
        <a-space>
          <a-button type="primary" size="small" @click="doUpdate(record)">修改</a-button>
          <a-popconfirm content="确定要删除该题目吗？" type="warning" @ok="doDelete(record)">
            <a-button status="danger" size="small">删除</a-button>
          </a-popconfirm>
        </a-space>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Question, QuestionControllerService, QuestionQueryRequest } from "../../../generated";
import message from "@arco-design/web-vue/es/message";
import { useRouter } from "vue-router";
import moment from "moment";

const dataList = ref([]);
const total = ref(0);
const loading = ref(false);
const searchParams = ref<QuestionQueryRequest>({
  title: "",
  tags: [],
  pageSize: 10,
  current: 1,
  sortField: "createTime",
  sortOrder: "descend"
});

const loadData = async () => {
  loading.value = true;
  const res = await QuestionControllerService.listQuestionByPageUsingPost(searchParams.value);
  if (res.code === 0) {
    dataList.value = res.data.records;
    total.value = res.data.total;
  } else {
    message.error("加载失败，" + res.message);
  }
  loading.value = false;
};

onMounted(() => {
  loadData();
});

const doSearch = () => {
  searchParams.value = {
    ...searchParams.value,
    current: 1
  };
  loadData();
};

const columns = [
  {
    title: "id",
    dataIndex: "id",
    width: 70
  },
  {
    title: "标题",
    dataIndex: "title",
    ellipsis: true,
    tooltip: true,
    width: 160
  },
  {
    title: "内容",
    dataIndex: "content",
    ellipsis: true,
    tooltip: true,
    width: 200
  },
  {
    title: "标签",
    slotName: "tags",
    width: 180
  },
  {
    title: "答案",
    dataIndex: "answer",
    ellipsis: true,
    tooltip: true,
    width: 150
  },
  {
    title: "提交数",
    dataIndex: "submitNum",
    width: 80
  },
  {
    title: "通过数",
    dataIndex: "acceptedNum",
    width: 80
  },
  {
    title: "判题配置",
    dataIndex: "judgeConfig",
    ellipsis: true,
    tooltip: true,
    width: 150
  },
  {
    title: "判题用例",
    dataIndex: "judgeCase",
    ellipsis: true,
    tooltip: true,
    width: 150
  },
  {
    title: "用户id",
    dataIndex: "userId",
    width: 80
  },
  {
    title: "创建时间",
    slotName: "createTime",
    width: 120
  },
  {
    title: "操作",
    slotName: "optional",
    width: 150,
    fixed: "right"
  }
];

const onPageChange = (page: number) => {
  searchParams.value = {
    ...searchParams.value,
    current: page
  };
  loadData();
};

const doDelete = async (question: Question) => {
  const res = await QuestionControllerService.deleteQuestionUsingPost({
    id: question.id
  });
  if (res.code === 0) {
    message.success("删除成功");
    loadData();
  } else {
    message.error("删除失败");
  }
};

const router = useRouter();

const doUpdate = (question: Question) => {
  router.push({
    path: "/update/question",
    query: {
      id: question.id
    }
  });
};

const doAdd = () => {
  router.push({
    path: "/add/question"
  });
};
</script>

<style scoped>
#manageQuestionView {
  max-width: 1400px;
  margin: 0 auto;
}
</style>
