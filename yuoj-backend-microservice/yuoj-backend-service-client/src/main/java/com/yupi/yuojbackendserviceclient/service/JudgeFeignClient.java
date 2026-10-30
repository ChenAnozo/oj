package com.yupi.yuojbackendserviceclient.service;


import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "yuoj-backend-judge-service", path="/api/judge/inner")
public interface JudgeFeignClient {

    @PostMapping("/do")
    void doJudge(@RequestParam("questionSubmitId") long questionSubmitId);
}
