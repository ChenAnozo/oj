package com.yupi.yuojbackendmodel.dto.judge;

import com.yupi.yuojbackendmodel.dto.question.JudgeCase;
import com.yupi.yuojbackendmodel.dto.question.JudgeConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.util.List;

/** 执行和判定所需的任务数据，不暴露完整数据库实体。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeTaskDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long questionSubmitId;
    private Long questionId;
    private String language;
    private String code;
    private List<JudgeCase> judgeCaseList;
    private JudgeConfig judgeConfig;
}
