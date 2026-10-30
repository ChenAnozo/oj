package com.yupi.yuojbackendmodel.dto.judge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

/** 未领取成功时不返回任务数据。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimJudgeTaskResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    private boolean claimed;
    private JudgeTaskDTO task;
}
