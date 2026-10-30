package com.yupi.yuojbackendjudgeservice.judge;

import cn.hutool.json.JSONUtil;
import com.yupi.yuojbackendcommon.common.ErrorCode;
import com.yupi.yuojbackendcommon.exception.BusinessException;
import com.yupi.yuojbackendjudgeservice.judge.codesandbox.CodeSandboxClient;
import com.yupi.yuojbackendjudgeservice.judge.codesandbox.CodeSandboxClientFactory;
import com.yupi.yuojbackendjudgeservice.judge.codesandbox.CodeSandboxClientLogDecorator;
import com.yupi.yuojbackendjudgeservice.judge.codesandbox.impl.DiscoveryCodeSandboxClient;
import org.slf4j.MDC;
import com.yupi.yuojbackendjudgeservice.judge.strategy.JudgeContext;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeRequest;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeResponse;
import com.yupi.yuojbackendmodel.codesandbox.JudgeInfo;
import com.yupi.yuojbackendmodel.dto.question.JudgeCase;
import com.yupi.yuojbackendmodel.dto.judge.ClaimJudgeTaskRequest;
import com.yupi.yuojbackendmodel.dto.judge.ClaimJudgeTaskResponse;
import com.yupi.yuojbackendmodel.dto.judge.JudgeTaskDTO;
import com.yupi.yuojbackendmodel.entity.QuestionSubmit;
import com.yupi.yuojbackendmodel.enums.QuestionSubmitStatusEnum;
import com.yupi.yuojbackendserviceclient.service.QuestionFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class JudgeServiceImpl implements JudgeService {

    @Resource
    private QuestionFeignClient questionFeignClient;

    @Resource
    private JudgeManager judgeManager;

    @Value("${codesandbox.type:example}")
    private String type;

    @Value("${codesandbox.url:http://127.0.0.1:8090/executeCode}")
    private String sandboxUrl;

    @Value("${codesandbox.auth-secret}")
    private String sandboxAuthSecret;

    @Resource
    private DiscoveryCodeSandboxClient discoveryCodeSandboxClient;


    @Override
    public void doJudge(long questionSubmitId) {
        ClaimJudgeTaskRequest request = new ClaimJudgeTaskRequest();
        request.setQuestionSubmitId(questionSubmitId);
        ClaimJudgeTaskResponse response = questionFeignClient.claimJudgeTask(request);
        if (response == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "领取判题任务响应为空");
        }
        if (!response.isClaimed()) {
            log.info("任务不可领取，跳过判题：questionSubmitId={}", questionSubmitId);
            return;
        }
        JudgeTaskDTO task = response.getTask();
        if (task == null || !Long.valueOf(questionSubmitId).equals(task.getQuestionSubmitId())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "领取判题任务数据异常");
        }
        Long questionId = task.getQuestionId();

        String language = task.getLanguage();
        String code = task.getCode();
        List<JudgeCase> judgeCaseList = task.getJudgeCaseList();

        List<String> inputList = judgeCaseList.stream().map(JudgeCase::getInput).collect(Collectors.toList());

        ExecuteCodeRequest executeCodeRequest = ExecuteCodeRequest.builder().code(code).language(language).inputList(inputList).build();



        ExecuteCodeResponse executeCodeResponse;
        try (MDC.MDCCloseable ignored = MDC.putCloseable("questionSubmitId", String.valueOf(questionSubmitId))) {
            CodeSandboxClient codeSandboxClient = createCodeSandboxClient();
            executeCodeResponse = codeSandboxClient.executeCode(executeCodeRequest);
        } catch (Exception e) {
            log.error("代码沙箱调用失败，questionSubmitId={}", questionSubmitId, e);
            QuestionSubmit failUpdate = new QuestionSubmit();
            failUpdate.setId(questionSubmitId);
            failUpdate.setStatus(QuestionSubmitStatusEnum.SYSTEM_ERROR.getValue());
            JudgeInfo judgeInfo = new JudgeInfo();
            failUpdate.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
            questionFeignClient.updateQuestionSubmitById(failUpdate);
            return;
        }


        if (executeCodeResponse.getOutputList() == null){
            JudgeInfo judgeInfo = executeCodeResponse.getJudgeInfo();
            if (judgeInfo == null) {
                judgeInfo = new JudgeInfo();
            }
            QuestionSubmit questionSubmitUpdate = new QuestionSubmit();
            questionSubmitUpdate.setId(questionSubmitId);
            questionSubmitUpdate.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
            questionSubmitUpdate.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
            boolean update = questionFeignClient.updateQuestionSubmitById(questionSubmitUpdate);
            if (!update) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "题目状态更新错误");
            }
        }
        else {
            List<String> outputList = executeCodeResponse.getOutputList();
            //System.out.println("!!!!!!!!!!" + outputList);

            JudgeContext judgeContext = new JudgeContext();
            judgeContext.setJudgeInfo(executeCodeResponse.getJudgeInfo());
            judgeContext.setInputList(inputList);
            judgeContext.setOutputList(outputList);
            judgeContext.setJudgeCaseList(judgeCaseList);
            judgeContext.setLanguage(language);
            judgeContext.setJudgeConfig(task.getJudgeConfig());
            JudgeInfo judgeInfo = judgeManager.doJudge(judgeContext);

            QuestionSubmit questionSubmitUpdate = new QuestionSubmit();
            questionSubmitUpdate.setId(questionSubmitId);
            questionSubmitUpdate.setQuestionId(questionId);
            questionSubmitUpdate.setStatus(QuestionSubmitStatusEnum.SUCCEED.getValue());
            questionSubmitUpdate.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
            boolean update = questionFeignClient.updateQuestionSubmitById(questionSubmitUpdate);
            if (!update) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "题目状态更新错误");
            }
        }

    }

    CodeSandboxClient createCodeSandboxClient() {
        return new CodeSandboxClientLogDecorator(
                CodeSandboxClientFactory.newInstance(type, sandboxUrl, sandboxAuthSecret, discoveryCodeSandboxClient));
    }
}
