package com.yupi.yuojbackendmodel.codesandbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExecuteCodeResponse {
    private List<String> outputList;

    private String message;

    // 状态码，对应 QuestionSubmitStatusEnum：0-等待中 1-判题中 2-沙箱执行成功 3-沙箱执行失败 4-系统错误
    private Integer status;

    private JudgeInfo judgeInfo;
}
