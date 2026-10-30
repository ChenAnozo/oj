package com.yupi.yuojbackendjudgeservice.judge.codesandbox.impl;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.yupi.yuojbackendcommon.common.ErrorCode;
import com.yupi.yuojbackendcommon.exception.BusinessException;
import com.yupi.yuojbackendjudgeservice.judge.codesandbox.CodeSandboxClient;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeRequest;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeResponse;
import org.apache.commons.lang3.StringUtils;

/**
 * 远程代码沙箱客户端（实际调用远程沙箱接口）
 */
public class HttpCodeSandboxClient implements CodeSandboxClient {

    private static final String AUTH_REQUEST_HEADER = "auth";

    private final String url;
    private final String authSecret;

    public HttpCodeSandboxClient() {
        this(System.getenv().getOrDefault("SANDBOX_URL", "http://127.0.0.1:8090/executeCode"),
                System.getenv("SANDBOX_AUTH_SECRET"));
    }

    public HttpCodeSandboxClient(String url, String authSecret) {
        if (StringUtils.isBlank(authSecret)) {
            throw new IllegalArgumentException("SANDBOX_AUTH_SECRET is required");
        }
        this.url = url;
        this.authSecret = authSecret;
    }
    /**
     *
     * @param executeCodeRequest
     * @return
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {

        /**
         *     private List<String> inputList;
         *
         *     private String code;
         *
         *     private String language;
         */

        String json = JSONUtil.toJsonStr(executeCodeRequest);
        String response = HttpUtil.createPost(url)
                .header(AUTH_REQUEST_HEADER, authSecret)
                .body(json)
                .setConnectionTimeout(2000)
                .timeout(30000)
                .execute()
                .body();

        if (StringUtils.isBlank(response)) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "执行远程代码沙箱错误：executeCode remoteSandbox error, message = " + response);
        }

        System.out.println("远程代码沙箱");
        return JSONUtil.toBean(response, ExecuteCodeResponse.class);
    }
}
