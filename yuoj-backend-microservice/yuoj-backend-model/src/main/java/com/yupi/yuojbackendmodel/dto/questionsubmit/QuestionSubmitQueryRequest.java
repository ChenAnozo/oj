package com.yupi.yuojbackendmodel.dto.questionsubmit;


import com.yupi.yuojbackendcommon.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = true)
public class QuestionSubmitQueryRequest extends PageRequest implements Serializable {
    /**
     * 编程语言
     */
    private String language;

    /**
     * 提交状态
     * 0 待判题
     * 1 判题中
     * 2 成功
     * 3 失败
     */
    private Integer status;

    /**
     * 题目 id
     */
    private Long questionId;

    /**
     * 用户 id
     */
    private Long userId;


    private static final long serialVersionUID = 1L;
}
