package com.yupi.yuojbackendjudgeservice.judge.strategy;


import com.yupi.yuojbackendmodel.codesandbox.JudgeInfo;

public interface JudgeStrategy {

    JudgeInfo doJudge(JudgeContext judgeContext);
}
