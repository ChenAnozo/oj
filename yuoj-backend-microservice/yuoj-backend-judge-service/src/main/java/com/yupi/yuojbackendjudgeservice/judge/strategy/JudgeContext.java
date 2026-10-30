package com.yupi.yuojbackendjudgeservice.judge.strategy;

import com.yupi.yuojbackendmodel.codesandbox.JudgeInfo;
import com.yupi.yuojbackendmodel.dto.question.JudgeCase;
import com.yupi.yuojbackendmodel.dto.question.JudgeConfig;
import lombok.Data;

import java.util.List;

@Data
public class JudgeContext {

    private JudgeInfo judgeInfo;

    private List<String> inputList;

    private List<String> outputList;

    private List<JudgeCase> judgeCaseList;

    private JudgeConfig judgeConfig;

    private String language;

}
