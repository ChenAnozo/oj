package com.yupi.yuojbackendmodel.enums;

import org.apache.commons.lang3.ObjectUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 判题信息消息枚举
 *
 * 判题结论（judgeInfo.message）的标准取值，统一使用英文 ACM 风格。
 * status 与 message 的对应关系：
 * - SUCCEED(2)      -> Accepted / Wrong Answer / Time Limit Exceeded / Memory Limit Exceeded
 * - FAILED(3)       -> Compile Error / Runtime Error
 * - SYSTEM_ERROR(4) -> message 留空
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

    /**
     * 获取值列表
     *
     * @return
     */
    public static List<String> getValues() {
        return Arrays.stream(values()).map(JudgeInfoMessageEnum::getValue).collect(Collectors.toList());
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value
     * @return
     */
    public static JudgeInfoMessageEnum getEnumByValue(String value) {
        if (ObjectUtils.isEmpty(value)) {
            return null;
        }
        for (JudgeInfoMessageEnum anEnum : JudgeInfoMessageEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }

    public String getValue() {
        return value;
    }
}
