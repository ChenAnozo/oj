import { OpenAPI } from "../../generated";

// Keep the generated client's default for the existing development workflow.
// An explicitly empty value selects same-origin requests through a proxy.
if (process.env.VUE_APP_API_BASE !== undefined) {
  OpenAPI.BASE = process.env.VUE_APP_API_BASE.replace(/\/+$/, "");
}

export function getJudgeWebSocketUrl(questionSubmitId: number): string {
  const url = new URL("/api/question/ws/judge", OpenAPI.BASE || window.location.origin);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.searchParams.set("questionSubmitId", String(questionSubmitId));
  return url.toString();
}
