package com.yupi.yuojbackendmodel.dto.judge;

import lombok.Data;
import java.io.Serializable;

/** 领取判题任务的请求。 */
@Data
public class ClaimJudgeTaskRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long questionSubmitId;
}
