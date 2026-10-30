<template>
  <section>
    <div class="workspace-heading">
      <router-link :to="returnPath">← 返回题库</router-link
      ><router-link :to="{ path: '/question_submit', query: { questionId: id } }">本题提交记录 ↗</router-link>
    </div>
    <a-alert v-if="loadError" type="error">{{ loadError }} <a-link @click="loadQuestion">重试</a-link></a-alert>
    <a-spin :loading="loading" style="display: block">
      <label v-if="question" class="pane-control"
        >题面宽度 <input v-model="paneWidth" type="range" min="35" max="65" aria-label="题面宽度"
      /></label>
      <div
        v-if="question"
        class="workspace"
        :style="{ gridTemplateColumns: paneWidth + 'fr ' + (100 - Number(paneWidth)) + 'fr' }"
      >
        <article class="panel problem-pane">
          <div class="eyebrow">THE CHALLENGE</div>
          <h1>{{ question.title }}</h1>
          <a-space wrap
            ><a-tag v-for="tag in question.tags" :key="tag" color="arcoblue">{{ tag }}</a-tag></a-space
          >
          <div class="limits">
            <span
              >时间 <strong>{{ question.judgeConfig?.timeLimit ?? "—" }} ms</strong></span
            ><span
              >内存 <strong>{{ question.judgeConfig?.memoryLimit ?? "—" }} KB</strong></span
            ><span v-if="question.judgeConfig?.stackLimit"
              >堆栈 <strong>{{ question.judgeConfig.stackLimit }} KB</strong></span
            >
          </div>
          <MdViewer :value="question.content || '暂无题目描述'" />
        </article>
        <div class="editor-pane">
          <div class="panel">
            <div class="editor-toolbar">
              <strong>代码编辑器</strong
              ><a-select v-model="language" aria-label="编程语言" :options="languageOptions" style="width: 120px" />
            </div>
            <CodeEditor :value="code" :language="language" :handle-change="changeCode" />
            <div class="submit-bar">
              <span class="muted" role="status">{{ draftStatus }}</span
              ><a-button
                type="primary"
                :loading="submitting || isInProgress(judgeStatus)"
                :disabled="!code.trim()"
                @click="submit"
                >{{ isInProgress(judgeStatus) ? "正在判题" : "提交代码" }}</a-button
              >
            </div>
          </div>
          <div class="panel result-panel" aria-live="polite">
            <div class="result-heading">
              <strong>判题结果</strong><a-tag :color="resultColor">{{ resultTitle }}</a-tag>
            </div>
            <template v-if="judgeStatus === SubmitStatusEnum.READY"
              ><p class="muted">准备好后提交代码，在这里查看判题反馈。</p></template
            >
            <template v-else-if="isInProgress(judgeStatus)"
              ><p>{{ statusText || "代码已提交，正在等待判题结果…" }}</p></template
            >
            <template v-else
              ><p>{{ resultHint }}</p>
              <div class="metrics">
                <span
                  >运行时间 <strong>{{ judgeInfo.time ?? "—" }} ms</strong></span
                ><span
                  >内存占用 <strong>{{ judgeInfo.memory ?? "—" }} KB</strong></span
                >
              </div></template
            >
            <a-alert v-if="trackingError" type="warning"
              >{{ trackingError }} <a-link @click="reconnect">重新获取结果</a-link></a-alert
            >
            <router-link
              v-if="submissionId"
              class="record-link"
              :to="{ path: '/question_submit', query: { questionId: id } }"
              >查看本题提交记录 →</router-link
            >
          </div>
        </div>
      </div>
    </a-spin>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from "vue";
import { useRoute, onBeforeRouteLeave } from "vue-router";
import { useStore } from "vuex";
import message from "@arco-design/web-vue/es/message";
import { QuestionControllerService, QuestionVO } from "../../../generated";
import CodeEditor from "@/components/CodeEditor.vue";
import MdViewer from "@/components/MdViewer.vue";
import { languageOptions } from "@/config/brand";
import { safeReturnPath } from "@/utils/navigation";
import {
  SubmitStatusEnum,
  isInProgress,
  getJudgeMessageText,
  getJudgeMessageColor,
  STATUS_DISPLAY
} from "@/constants/judgeConstants";
import { useJudgeWebSocket } from "@/composables/useJudgeWebSocket";

const props = defineProps<{ id: string }>();
const route = useRoute();
const store = useStore();
const returnPath = computed(() => {
  const path = safeReturnPath(route.query.from);
  return path.split("?")[0] === "/questions" ? path : "/questions";
});
const question = ref<QuestionVO>();
const paneWidth = ref(50);
const loading = ref(false);
const loadError = ref("");
let loadVersion = 0;
const loadQuestion = async () => {
  const version = ++loadVersion;
  loading.value = true;
  loadError.value = "";
  question.value = undefined;
  try {
    // Keep long IDs as strings at runtime to avoid loss of precision.
    const res = await QuestionControllerService.getQuestionVoByIdUsingGet(props.id as unknown as number);
    if (version !== loadVersion) return;
    if (res.code !== 0 || !res.data) throw new Error(res.message || "题目不存在或无法访问");
    question.value = res.data;
  } catch (e) {
    if (version === loadVersion) loadError.value = e instanceof Error ? e.message : "题目加载失败，请重试";
  } finally {
    if (version === loadVersion) loading.value = false;
  }
};
const language = ref("java");
const code = ref("");
const draftStatus = ref("草稿保存在当前浏览器");
const draftKey = computed(
  () => "codeorder:draft:" + (store.state.user.loginUser?.id || "guest") + ":" + props.id + ":" + language.value
);
let saveTimer: ReturnType<typeof setTimeout>;
const saveDraft = (key = draftKey.value, value = code.value) => {
  try {
    localStorage.setItem(key, value);
    draftStatus.value = "草稿已保存到当前浏览器";
  } catch {
    draftStatus.value = "草稿保存失败，请及时复制代码";
  }
};
watch(
  draftKey,
  (_key, oldKey) => {
    clearTimeout(saveTimer);
    if (oldKey) saveDraft(oldKey);
    try {
      code.value = localStorage.getItem(draftKey.value) || "";
      draftStatus.value = code.value ? "已恢复上次的代码草稿" : "草稿保存在当前浏览器";
    } catch {
      code.value = "";
      draftStatus.value = "浏览器无法保存草稿，请及时复制代码";
    }
  },
  { immediate: true, flush: "sync" }
);
const changeCode = (value: string) => {
  code.value = value;
  draftStatus.value = "正在保存草稿…";
  clearTimeout(saveTimer);
  const key = draftKey.value;
  saveTimer = setTimeout(() => saveDraft(key, value), 400);
};
const flushDraft = () => {
  clearTimeout(saveTimer);
  saveDraft();
};
window.addEventListener("pagehide", flushDraft);
onBeforeRouteLeave(flushDraft);
onBeforeUnmount(() => {
  flushDraft();
  window.removeEventListener("pagehide", flushDraft);
});

const { judgeStatus, judgeMessage, judgeInfo, statusText, trackingError, connect, reset } = useJudgeWebSocket();
const submitting = ref(false);
const submissionId = ref<number>();
const resultTitle = computed(() =>
  judgeStatus.value === SubmitStatusEnum.SYSTEM_ERROR
    ? "系统错误"
    : isInProgress(judgeStatus.value)
    ? STATUS_DISPLAY[judgeStatus.value]
    : judgeStatus.value === SubmitStatusEnum.READY
    ? "等待提交"
    : getJudgeMessageText(judgeMessage.value)
);
const resultColor = computed(() =>
  judgeStatus.value === SubmitStatusEnum.SYSTEM_ERROR
    ? "red"
    : isInProgress(judgeStatus.value)
    ? "blue"
    : getJudgeMessageColor(judgeMessage.value)
);
const resultHint = computed(
  () =>
    ({
      Accepted: "全部测试通过，继续挑战下一道题吧。",
      "Wrong Answer": "输出与预期不一致，检查边界情况与输出格式后再试。",
      "Compile Error": "代码未能编译，请检查语法、入口和所选编程语言。",
      "Time Limit Exceeded": "运行时间超过限制，尝试优化算法复杂度。",
      "Memory Limit Exceeded": "内存占用超过限制，检查数据规模与空间使用。",
      "Runtime Error": "程序运行异常，请检查数组越界、空值或非法运算。"
    }[judgeMessage.value] ||
    (judgeStatus.value === SubmitStatusEnum.SYSTEM_ERROR
      ? "平台服务异常，请稍后重新提交。"
      : "请结合判题结果检查代码后重试。"))
);
const submit = async () => {
  if (submitting.value || isInProgress(judgeStatus.value) || !code.value.trim() || !question.value?.id) return;
  submitting.value = true;
  const questionId = props.id;
  flushDraft();
  try {
    const res = await QuestionControllerService.doQuestionSubmitUsingPost({
      questionId: question.value.id,
      language: language.value,
      code: code.value
    });
    if (res.code !== 0 || !res.data) throw new Error(res.message || "提交失败");
    if (questionId !== props.id) return;
    submissionId.value = res.data;
    connect(res.data);
  } catch (e) {
    message.error(e instanceof Error ? e.message : "提交失败，请稍后重试");
  } finally {
    submitting.value = false;
  }
};
const reconnect = () => {
  if (submissionId.value) connect(submissionId.value);
};
watch(
  () => props.id,
  () => {
    reset();
    submissionId.value = undefined;
    loadQuestion();
  },
  { immediate: true }
);
</script>
<style scoped>
.pane-control {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 10px;
  color: #778197;
  font-size: 12px;
  margin: -6px 0 14px;
}
.pane-control input {
  accent-color: #4658d6;
  width: 110px;
}
.workspace-heading {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;
  font-size: 13px;
}
.workspace {
  display: grid;
  grid-template-columns: minmax(300px, 1fr) minmax(380px, 1fr);
  gap: 20px;
  align-items: start;
}
.problem-pane {
  overflow: auto;
  min-width: 0;
  max-width: 100%;
}
.problem-pane h1 {
  margin: 10px 0 18px;
  font-size: 27px;
}
.limits {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  padding: 18px 0;
  margin: 6px 0 18px;
  border-bottom: 1px solid #edf0f5;
  font-size: 12px;
  color: #778197;
}
.limits strong {
  color: #39445c;
  font-weight: 500;
}
.editor-pane {
  min-width: 0;
}
.editor-toolbar,
.submit-bar,
.result-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.editor-toolbar {
  margin-bottom: 16px;
}
.submit-bar {
  margin-top: 18px;
}
.result-panel {
  margin-top: 20px;
}
.result-panel p {
  font-size: 13px;
  line-height: 1.8;
  color: #778197;
}
.metrics {
  display: flex;
  gap: 24px;
  font-size: 12px;
  color: #778197;
}
.metrics strong {
  color: #39445c;
  margin-left: 6px;
}
.record-link {
  display: inline-block;
  font-size: 12px;
  margin-top: 16px;
}
@media (max-width: 900px) {
  .pane-control {
    display: none;
  }
  .workspace {
    display: flex;
    flex-direction: column;
  }
  .problem-pane,
  .editor-pane {
    width: 100%;
    min-width: 0;
    resize: none;
  }
}
</style>
