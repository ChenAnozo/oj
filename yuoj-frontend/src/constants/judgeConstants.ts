// src/constants/judgeConstants.ts

/**
 * 提交状态枚举，与后端 QuestionSubmitStatusEnum 保持一致
 * READY 为前端专用状态（后端不返回此值），表示"就绪，可提交"
 */
export const SubmitStatusEnum = {
  READY: -1, // 前端专用：就绪，可以提交
  WAITING: 0, // 后端：等待中
  RUNNING: 1, // 后端：判题中
  SUCCEED: 2, // 后端：沙箱执行成功
  FAILED: 3, // 后端：沙箱执行失败（用户代码问题）
  SYSTEM_ERROR: 4 // 后端：系统错误
} as const;

export type SubmitStatus = (typeof SubmitStatusEnum)[keyof typeof SubmitStatusEnum];

/**
 * 状态码 → 展示文案
 */
export const STATUS_DISPLAY: Record<number, string> = {
  [SubmitStatusEnum.READY]: "就绪",
  [SubmitStatusEnum.WAITING]: "等待中",
  [SubmitStatusEnum.RUNNING]: "判题中",
  [SubmitStatusEnum.SUCCEED]: "判题完成",
  [SubmitStatusEnum.FAILED]: "执行失败",
  [SubmitStatusEnum.SYSTEM_ERROR]: "系统错误"
};

/**
 * 状态码 → Arco Design 标签颜色
 */
export const STATUS_COLOR: Record<number, string> = {
  [SubmitStatusEnum.READY]: "gray",
  [SubmitStatusEnum.WAITING]: "blue",
  [SubmitStatusEnum.RUNNING]: "blue",
  [SubmitStatusEnum.SUCCEED]: "green",
  [SubmitStatusEnum.FAILED]: "red",
  [SubmitStatusEnum.SYSTEM_ERROR]: "red"
};

/**
 * judgeInfo.message → 中文展示文案（与后端 JudgeInfoMessageEnum 值对应）
 */
export const JUDGE_MESSAGE_DISPLAY: Record<string, string> = {
  Accepted: "通过",
  "Wrong Answer": "答案错误",
  "Compile Error": "编译失败",
  "Time Limit Exceeded": "时间超限",
  "Memory Limit Exceeded": "内存超限",
  "Runtime Error": "运行错误"
};

/**
 * judgeInfo.message → 标签颜色
 */
export const JUDGE_MESSAGE_COLOR: Record<string, string> = {
  Accepted: "green",
  "Wrong Answer": "red",
  "Compile Error": "red",
  "Time Limit Exceeded": "orangered",
  "Memory Limit Exceeded": "orangered",
  "Runtime Error": "red"
};

/**
 * 根据 judgeInfo.message 获取展示文案（带兜底）
 */
export function getJudgeMessageText(message?: string): string {
  if (!message) return "未知";
  return JUDGE_MESSAGE_DISPLAY[message] ?? message;
}

/**
 * 根据 judgeInfo.message 获取标签颜色（带兜底）
 */
export function getJudgeMessageColor(message?: string): string {
  if (!message) return "gray";
  return JUDGE_MESSAGE_COLOR[message] ?? "gray";
}

/**
 * 判断 status 是否为终态（轮询应停止，结果应展示）
 * 注意：READY(-1) 不是终态
 */
export function isFinalStatus(status: number): boolean {
  const finalStatuses: number[] = [SubmitStatusEnum.SUCCEED, SubmitStatusEnum.FAILED, SubmitStatusEnum.SYSTEM_ERROR];
  return finalStatuses.includes(status);
}

/**
 * 判断 status 是否为进行中（轮询应继续）
 */
export function isInProgress(status: number): boolean {
  const inProgressStatuses: number[] = [SubmitStatusEnum.WAITING, SubmitStatusEnum.RUNNING];
  return inProgressStatuses.includes(status);
}
