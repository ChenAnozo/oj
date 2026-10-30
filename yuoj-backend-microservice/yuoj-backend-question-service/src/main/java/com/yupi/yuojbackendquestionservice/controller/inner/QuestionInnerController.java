package com.yupi.yuojbackendquestionservice.controller.inner;

import com.yupi.yuojbackendcommon.common.ErrorCode;
import com.yupi.yuojbackendcommon.exception.BusinessException;
import com.yupi.yuojbackendmodel.dto.judge.ClaimJudgeTaskRequest;
import com.yupi.yuojbackendmodel.dto.judge.ClaimJudgeTaskResponse;

import com.yupi.yuojbackendmodel.entity.Question;
import com.yupi.yuojbackendmodel.entity.QuestionSubmit;
import com.yupi.yuojbackendquestionservice.service.QuestionService;
import com.yupi.yuojbackendquestionservice.service.QuestionSubmitService;
import com.yupi.yuojbackendserviceclient.service.QuestionFeignClient;
import com.yupi.yuojbackendserviceclient.service.UserFeignClient;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/inner")
public class QuestionInnerController implements QuestionFeignClient {

    @Resource
    QuestionService questionService;

    @Resource
    QuestionSubmitService questionSubmitService;

    @Override
    @PostMapping("/judge_task/claim")
    public ClaimJudgeTaskResponse claimJudgeTask(@RequestBody ClaimJudgeTaskRequest request) {
        if (request == null || request.getQuestionSubmitId() == null
                || request.getQuestionSubmitId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        return questionSubmitService.claimJudgeTask(request.getQuestionSubmitId());
    }

    @Override
    @GetMapping("/get/id")
    public Question getQuestionById(@RequestParam("questionId") long questionId){
        return questionService.getById(questionId);
    }

    @Override
    @GetMapping("/question_submit/get/id")
    public QuestionSubmit getQuestionSubmitById(@RequestParam("questionId") long questionSubmitId){
        return questionSubmitService.getById(questionSubmitId);
    }

    @Override
    @PostMapping("/question_submit/update")
    public boolean updateQuestionSubmitById(@RequestBody QuestionSubmit questionSubmit){
        return questionSubmitService.updateSubmitAndNotify(questionSubmit);
    }

    @Override
    @PostMapping("/incr/accepted")
    public void incrAcceptedNum(long questionId) {
        questionService.incrAcceptedNum(questionId);
    }

    @Override
    @PostMapping("/question_submit/update/status/cas")
    public boolean compareAndSetSubmitStatus(long id, Integer expectStatus, Integer newStatus) {
        return questionSubmitService.compareAndSetStatus(id, expectStatus, newStatus);
    }


}
