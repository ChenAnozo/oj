package com.yupi.yuojbackendmodel.dto.question;

import lombok.Data;

/**
 * 题目配置
 */
@Data
public class JudgeConfig {

    /**
     * 时间限制(ms)
     */
    private Long timeLimit;

    /**
     * 空间限制(kb)
     */
    private Long memoryLimit;

    /**
     * 堆栈限制(Kb)
     */
    private Long stackLimit;
}
