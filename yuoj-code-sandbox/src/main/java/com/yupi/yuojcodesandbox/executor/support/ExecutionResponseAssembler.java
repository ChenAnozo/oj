package com.yupi.yuojcodesandbox.executor.support;

import com.yupi.yuojcodesandbox.enums.JudgeInfoMessageEnum;
import com.yupi.yuojcodesandbox.enums.QuestionSubmitStatusEnum;
import com.yupi.yuojcodesandbox.model.*;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class ExecutionResponseAssembler {
    public ExecuteCodeResponse error(String message, JudgeInfoMessageEnum type) {
        JudgeInfo info = new JudgeInfo();
        info.setMessage(type.getValue());
        return ExecuteCodeResponse.builder().message(message).outputList(null)
                .status(QuestionSubmitStatusEnum.FAILED.getValue()).judgeInfo(info).build();
    }

    public ExecuteCodeResponse assemble(List<ExecuteMessage> messages) {
        List<String> outputs = new ArrayList<>();
        long time = 0, memory = 0;
        for (ExecuteMessage message : messages) {
            if (message.getExitValue() != null && message.getExitValue() != 0) {
                return error(message.getErrorMessage(), JudgeInfoMessageEnum.RUNTIME_ERROR);
            }
            outputs.add(message.getMessage());
            if (message.getTime() != null) { time = Math.max(time, message.getTime()); }
            if (message.getMemory() != null) { memory = Math.max(memory, message.getMemory()); }
        }
        JudgeInfo info = new JudgeInfo();
        info.setTime(time);
        info.setMemory(memory / 1024);
        return ExecuteCodeResponse.builder().outputList(outputs).judgeInfo(info)
                .status(QuestionSubmitStatusEnum.SUCCEED.getValue()).build();
    }
}
