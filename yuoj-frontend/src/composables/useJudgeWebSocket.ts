import { ref, onUnmounted } from "vue";
import { SubmitStatusEnum, isFinalStatus } from "@/constants/judgeConstants";
import { JudgeInfo, QuestionControllerService } from "../../generated";
import { getJudgeWebSocketUrl } from "@/config/api";

// Polling reconciles missed messages, early results and interrupted sockets.
export function useJudgeWebSocket() {
  const judgeStatus = ref<number>(SubmitStatusEnum.READY);
  const judgeMessage = ref("");
  const judgeInfo = ref<JudgeInfo>({});
  const statusText = ref("");
  const trackingError = ref("");
  let ws: WebSocket | null = null;
  let timer: ReturnType<typeof setTimeout>;
  let generation = 0;

  const disconnect = () => {
    generation++;
    clearTimeout(timer);
    if (ws) {
      ws.onmessage = null;
      ws.onerror = null;
      ws.onclose = null;
      ws.close();
      ws = null;
    }
  };
  const apply = (status: number, info?: JudgeInfo, text?: string) => {
    if (typeof status !== "number") return;
    judgeStatus.value = status;
    statusText.value = text || "";
    if (info) {
      judgeInfo.value = info;
      judgeMessage.value = info.message || "";
    }
    if (isFinalStatus(status)) {
      trackingError.value = "";
      disconnect();
    }
  };
  const connect = (id: number) => {
    disconnect();
    judgeStatus.value = SubmitStatusEnum.WAITING;
    judgeMessage.value = "";
    judgeInfo.value = {};
    trackingError.value = "";
    const token = generation;
    const started = Date.now();
    const poll = async () => {
      if (token !== generation) return;
      try {
        const res = await QuestionControllerService.getQuestionSubmitByIdUsingGet(id);
        if (token !== generation) return;
        if (res.code === 0 && res.data) {
          trackingError.value = "";
          apply(res.data.status, res.data.judgeInfo);
        } else trackingError.value = res.message || "暂时无法获取结果，正在重试";
      } catch {
        if (token === generation) trackingError.value = "连接暂时中断，正在重新获取结果";
      }
      if (token !== generation) return;
      if (Date.now() - started > 300000) {
        trackingError.value = "等待时间较长，请重新获取结果，或前往提交记录查看。";
        disconnect();
        return;
      }
      timer = setTimeout(poll, 3000);
    };
    try {
      ws = new WebSocket(getJudgeWebSocketUrl(id));
      ws.onmessage = (event) => {
        if (token !== generation) return;
        try {
          const data = JSON.parse(event.data);
          apply(data.status, data.judgeInfo, data.statusText);
        } catch {
          /* Polling will reconcile malformed or missed events. */
        }
      };
      ws.onerror = () => {
        /* HTTP polling remains active. */
      };
    } catch {
      /* HTTP polling also works without a WebSocket connection. */
    }
    poll();
  };
  const reset = () => {
    disconnect();
    judgeStatus.value = SubmitStatusEnum.READY;
    judgeMessage.value = "";
    judgeInfo.value = {};
    statusText.value = "";
    trackingError.value = "";
  };
  onUnmounted(disconnect);
  return { judgeStatus, judgeMessage, judgeInfo, statusText, trackingError, connect, reset, isFinalStatus };
}
