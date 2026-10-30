<template>
  <div id="addQuestionView">
    <h2 class="page-title">{{ updatePage ? '更新题目' : '创建题目' }}</h2>

    <a-form :model="form" label-align="left" :rules="rules" ref="formRef">
      <a-card title="基本信息" :bordered="false" class="section-card">
        <a-form-item field="title" label="标题">
          <a-input v-model="form.title" placeholder="请输入标题" />
        </a-form-item>
        <a-form-item field="tags" label="标签">
          <a-input-tag v-model="form.tags" placeholder="请选择标签" allow-clear />
        </a-form-item>
        <a-form-item field="content" label="题目内容">
          <MdEditor :value="form.content" :handle-change="onContentChange" />
        </a-form-item>
        <a-form-item field="answer" label="答案">
          <MdEditor :value="form.answer" :handle-change="onAnswerChange" />
        </a-form-item>
      </a-card>

      <a-card title="判题配置" :bordered="false" class="section-card">
        <a-row :gutter="24">
          <a-col :span="8">
            <a-form-item field="judgeConfig.timeLimit" label="时间限制">
              <a-space>
                <a-input-number
                  v-model="form.judgeConfig.timeLimit"
                  placeholder="请输入时间限制"
                  mode="button"
                  :min="0"
                  size="large"
                />
                <span class="unit-label">ms</span>
              </a-space>
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item field="judgeConfig.memoryLimit" label="内存限制">
              <a-space>
                <a-input-number
                  v-model="form.judgeConfig.memoryLimit"
                  placeholder="请输入内存限制"
                  mode="button"
                  :min="0"
                  size="large"
                />
                <span class="unit-label">KB</span>
              </a-space>
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item field="judgeConfig.stackLimit" label="堆栈限制">
              <a-space>
                <a-input-number
                  v-model="form.judgeConfig.stackLimit"
                  placeholder="请输入堆栈限制"
                  mode="button"
                  :min="0"
                  size="large"
                />
                <span class="unit-label">KB</span>
              </a-space>
            </a-form-item>
          </a-col>
        </a-row>
      </a-card>

      <a-card title="测试用例配置" :bordered="false" class="section-card">
        <a-card
          v-for="(judgeCaseItem, index) of form.judgeCase"
          :key="index"
          size="small"
          :bordered="true"
          class="test-case-card"
        >
          <template #title> 测试用例 #{{ index + 1 }} </template>
          <template #extra>
            <a-button
              type="text"
              status="danger"
              size="small"
              @click="handleDelete(index)"
              :disabled="form.judgeCase.length === 1"
            >
              删除
            </a-button>
          </template>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item :field="`form.judgeCase[${index}].input`" label="输入用例">
                <a-textarea
                  v-model="judgeCaseItem.input"
                  placeholder="请输入测试输入用例"
                  :auto-size="{ minRows: 2, maxRows: 6 }"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item :field="`form.judgeCase[${index}].output`" label="输出用例">
                <a-textarea
                  v-model="judgeCaseItem.output"
                  placeholder="请输入测试输出用例"
                  :auto-size="{ minRows: 2, maxRows: 6 }"
                />
              </a-form-item>
            </a-col>
          </a-row>
        </a-card>
        <a-button @click="handleAdd" type="outline" status="success" long> 新增测试用例 </a-button>
      </a-card>

      <div class="submit-wrapper">
        <a-button type="primary" size="large" style="min-width: 200px" :loading="loading" @click="handleSubmit">
          {{ updatePage ? '更新' : '创建' }}
        </a-button>
      </div>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import MdEditor from "@/components/MdEditor.vue";
import { QuestionControllerService } from "../../../generated";
import message from "@arco-design/web-vue/es/message";
import { Modal } from "@arco-design/web-vue";
import { useRoute } from "vue-router";

const route = useRoute();
const updatePage = route.path.includes("update");

const formRef = ref();
const loading = ref(false);

const form = ref({
  title: "",
  tags: [],
  answer: "",
  content: "",
  judgeConfig: {
    memoryLimit: 65536,
    stackLimit: 8192,
    timeLimit: 1000
  },
  judgeCase: [
    {
      input: "",
      output: ""
    }
  ]
});

const rules = {
  title: [{ required: true, message: "请输入标题" }],
  content: [{ required: true, message: "请输入题目内容" }]
};

const loadData = async () => {
  const id = route.query.id;
  if (!id) {
    return;
  }
  const res = await QuestionControllerService.getQuestionByIdUsingGet(id as any);
  if (res.code === 0) {
    form.value = res.data as any;
    if (!form.value.judgeCase) {
      form.value.judgeCase = [
        {
          input: "",
          output: ""
        }
      ];
    } else {
      form.value.judgeCase = JSON.parse(form.value.judgeCase as any);
    }
    if (!form.value.judgeConfig) {
      form.value.judgeConfig = {
        memoryLimit: 65536,
        stackLimit: 8192,
        timeLimit: 1000
      };
    } else {
      form.value.judgeConfig = JSON.parse(form.value.judgeConfig as any);
    }
    if (!form.value.tags) {
      form.value.tags = [];
    } else {
      form.value.tags = JSON.parse(form.value.tags as any);
    }
  } else {
    message.error("加载失败，" + res.message);
  }
};

onMounted(() => {
  loadData();
});

const handleSubmit = async () => {
  const errors = await formRef.value.validate();
  if (errors) {
    return;
  }
  Modal.confirm({
    title: "确认提交",
    content: updatePage ? "确定要更新该题目吗？" : "确定要创建该题目吗？",
    onOk: doSubmit
  });
};

const doSubmit = async () => {
  loading.value = true;
  if (updatePage) {
    const res = await QuestionControllerService.updateQuestionUsingPost(form.value);
    if (res.code === 0) {
      message.success("更新成功");
    } else {
      message.error("更新失败，" + res.message);
    }
  } else {
    const res = await QuestionControllerService.addQuestionUsingPost(form.value);
    if (res.code === 0) {
      message.success("创建成功");
    } else {
      message.error("创建失败，" + res.message);
    }
  }
  loading.value = false;
};

const handleAdd = () => {
  form.value.judgeCase.push({
    input: "",
    output: ""
  });
};

const handleDelete = (index: number) => {
  form.value.judgeCase.splice(index, 1);
};

const onContentChange = (value: string) => {
  form.value.content = value;
};

const onAnswerChange = (value: string) => {
  form.value.answer = value;
};
</script>

<style scoped>
#addQuestionView {
  max-width: 1280px;
  margin: 0 auto;
}

.page-title {
  text-align: center;
  margin-bottom: 24px;
  font-size: 24px;
  color: var(--color-text-1);
}

.section-card {
  margin-bottom: 16px;
}

.test-case-card {
  margin-bottom: 12px;
}

.submit-wrapper {
  text-align: center;
  margin-top: 24px;
}

.unit-label {
  color: #86909c;
  font-size: 14px;
}
</style>
