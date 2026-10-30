package com.yupi.yuojbackendjudgeservice.judge;

import com.yupi.yuojbackendjudgeservice.judge.strategy.DefaultJudgeStrategy;
import com.yupi.yuojbackendjudgeservice.judge.strategy.JavaLanguageJudgeStrategy;
import com.yupi.yuojbackendjudgeservice.judge.strategy.JudgeContext;
import com.yupi.yuojbackendjudgeservice.judge.strategy.JudgeStrategy;
import com.yupi.yuojbackendmodel.codesandbox.JudgeInfo;
import org.springframework.stereotype.Service;

@Service
public class JudgeManager {

    JudgeInfo doJudge(JudgeContext judgeContext){
        String language = judgeContext.getLanguage();
        JudgeStrategy judgeStrategy = new DefaultJudgeStrategy();
        if ("java".equals(language)) {
            judgeStrategy = new JavaLanguageJudgeStrategy();
        }
        if ("cpp".equals(language)) {
            judgeStrategy = new DefaultJudgeStrategy();
        }

        return judgeStrategy.doJudge(judgeContext);
    }
}
