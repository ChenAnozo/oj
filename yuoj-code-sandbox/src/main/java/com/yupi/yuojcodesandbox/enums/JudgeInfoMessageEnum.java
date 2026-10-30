package com.yupi.yuojcodesandbox.enums;

/**
 * 判题信息消息枚举
 *
 * 判题结论（judgeInfo.message）的标准取值，与后端 JudgeInfoMessageEnum 保持一致，统一使用英文 ACM 风格。
 */
public enum JudgeInfoMessageEnum {

    ACCEPTED("Accepted"),
    WRONG_ANSWER("Wrong Answer"),
    COMPILE_ERROR("Compile Error"),
    TIME_LIMIT_EXCEEDED("Time Limit Exceeded"),
    MEMORY_LIMIT_EXCEEDED("Memory Limit Exceeded"),
    RUNTIME_ERROR("Runtime Error");

    private final String value;

    JudgeInfoMessageEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
