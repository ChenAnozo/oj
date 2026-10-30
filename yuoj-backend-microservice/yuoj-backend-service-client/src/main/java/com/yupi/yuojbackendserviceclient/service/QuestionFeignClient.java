package com.yupi.yuojbackendserviceclient.service;

import com.yupi.yuojbackendmodel.dto.judge.ClaimJudgeTaskRequest;
import com.yupi.yuojbackendmodel.dto.judge.ClaimJudgeTaskResponse;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.yupi.yuojbackendmodel.dto.question.QuestionQueryRequest;
import com.yupi.yuojbackendmodel.entity.Question;
import com.yupi.yuojbackendmodel.entity.QuestionSubmit;
import com.yupi.yuojbackendmodel.vo.QuestionVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;


import javax.servlet.http.HttpServletRequest;

/**
 * @author 93273
 * @description 针对表【question(题目)】的数据库操作Service
 * @createDate 2026-03-07 21:20:06
 */
@FeignClient(name="yuoj-backend-question-service", path = "api/question/inner")
public interface QuestionFeignClient{

    @PostMapping("/judge_task/claim")
    ClaimJudgeTaskResponse claimJudgeTask(@RequestBody ClaimJudgeTaskRequest request);

    @GetMapping("/get/id")
    Question getQuestionById(@RequestParam("questionId") long questionId);

    @GetMapping("/question_submit/get/id")
    QuestionSubmit getQuestionSubmitById(@RequestParam("questionId") long questionSubmitId);

    @PostMapping("/question_submit/update")
    boolean updateQuestionSubmitById(@RequestBody QuestionSubmit questionSubmit);

    @PostMapping("/incr/accepted")
    void incrAcceptedNum(@RequestParam("questionId") long questionId);

    @PostMapping("/question_submit/update/status/cas")
    boolean compareAndSetSubmitStatus(@RequestParam("id") long id,
                                      @RequestParam("expectStatus") Integer expectStatus,
                                      @RequestParam("newStatus") Integer newStatus);

}
