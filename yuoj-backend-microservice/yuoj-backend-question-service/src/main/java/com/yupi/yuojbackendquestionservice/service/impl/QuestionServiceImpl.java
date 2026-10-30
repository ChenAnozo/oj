package com.yupi.yuojbackendquestionservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.yuojbackendcommon.common.ErrorCode;
import com.yupi.yuojbackendcommon.constant.CommonConstant;
import com.yupi.yuojbackendcommon.exception.BusinessException;
import com.yupi.yuojbackendcommon.exception.ThrowUtils;
import com.yupi.yuojbackendcommon.utils.SqlUtils;
import com.yupi.yuojbackendmodel.dto.question.QuestionQueryRequest;
import com.yupi.yuojbackendmodel.entity.Question;

import com.yupi.yuojbackendmodel.entity.User;
import com.yupi.yuojbackendmodel.vo.QuestionVO;
import com.yupi.yuojbackendmodel.vo.UserVO;
import com.yupi.yuojbackendquestionservice.mapper.QuestionMapper;
import com.yupi.yuojbackendquestionservice.service.QuestionService;
import com.yupi.yuojbackendserviceclient.service.UserFeignClient;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author 93273
 * @description 针对表【question(题目)】的数据库操作Service实现
 * @createDate 2026-03-07 21:20:06
 */
@Service
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question>
        implements QuestionService {

    @Resource
    private UserFeignClient userFeignClient;

    @Value("${judge.case-max-bytes:1048576}")
    private int judgeCaseMaxBytes = 1024 * 1024;


    /**
     * 校验题目是否合法
     *
     * @param question
     * @param add
     */
    @Override
    public void validQuestion(Question question, boolean add) {


        if (question == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        String title = question.getTitle();
        String content = question.getContent();
        String tags = question.getTags();
        String answer = question.getAnswer();
        String judgeCase = question.getJudgeCase();
        String judgeConfig = question.getJudgeConfig();


        // 创建时，参数不能为空
        if (add) {
            ThrowUtils.throwIf(StringUtils.isAnyBlank(title, content, tags), ErrorCode.PARAMS_ERROR);
        }
        // 有参数则校验
        if (StringUtils.isNotBlank(title) && title.length() > 80) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "标题过长");
        }
        if (StringUtils.isNotBlank(content) && content.length() > 8192) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "内容过长");
        }

        if (StringUtils.isNotBlank(answer) && answer.length() > 8192) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "答案过长");
        }

        if (StringUtils.isNotBlank(judgeCase)
                && judgeCase.getBytes(StandardCharsets.UTF_8).length > judgeCaseMaxBytes) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "判题用例过长");
        }

        if (StringUtils.isNotBlank(judgeConfig) && judgeConfig.length() > 8192) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "判题配置过长");
        }
    }

    /**
     * 获取查询包装类
     *
     * @param questionQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<Question> getQueryWrapper(QuestionQueryRequest questionQueryRequest) {
        QueryWrapper<Question> queryWrapper = new QueryWrapper<>();

        Long id = questionQueryRequest.getId();
        String title = questionQueryRequest.getTitle();
        String content = questionQueryRequest.getContent();
        List<String> tags = questionQueryRequest.getTags();
        String answer = questionQueryRequest.getAnswer();
        Long userId = questionQueryRequest.getUserId();
        int current = questionQueryRequest.getCurrent();
        int pageSize = questionQueryRequest.getPageSize();
        String sortField = questionQueryRequest.getSortField();
        String sortOrder = questionQueryRequest.getSortOrder();


        queryWrapper.like(StringUtils.isNotBlank(title), "title", title);
        queryWrapper.like(StringUtils.isNotBlank(content), "content", content);
        queryWrapper.like(StringUtils.isNotBlank(answer), "answer", content);
        if (CollUtil.isNotEmpty(tags)) {
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        queryWrapper.eq(ObjectUtils.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }


    @Override
    public QuestionVO getQuestionVO(Question question, HttpServletRequest request) {
        QuestionVO questionVO = QuestionVO.objToVo(question);

        // 1. 关联查询用户信息
        Long userId = question.getUserId();
        User user = null;
        if (userId != null && userId > 0) {
            user = userFeignClient.getById(userId);
        }
        UserVO userVO = userFeignClient.getUserVO(user);
        questionVO.setUserVO(userVO);

        return questionVO;
    }

    @Override
    public Page<QuestionVO> getQuestionVOPage(Page<Question> questionPage, HttpServletRequest request) {
        // 从Page中获取当前的题目列表(getRecords)
        List<Question> questionList = questionPage.getRecords();


        Page<QuestionVO> questionVOPage = new Page<>(questionPage.getCurrent(), questionPage.getSize(), questionPage.getTotal());
        if (CollUtil.isEmpty(questionList)) {
            return questionVOPage;
        }

        Set<Long> userIdSet = questionList.stream().map(Question::getUserId).collect(Collectors.toSet());

        // 根据去重后的 userId 集合，批量查询用户列表 (userService.listByIds)
        // 然后将查询结果流式处理，按照 User::getId 进行分组，生成 Map<Long, List<User>>
        // key 是 userId，value 是该 ID 对应的用户列表 (理论上 ID 唯一，list 长度应为 1)
        Map<Long, List<User>> userIdUserListMap = userFeignClient.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));

        // 填充信息
        List<QuestionVO> questionVOList = questionList.stream().map(question -> {
            // 将 Question 实体对象的基本属性拷贝转换为 QuestionVO 对象（除了UserVO）
            QuestionVO questionVO = QuestionVO.objToVo(question);
            Long userId = question.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                // 如果存在，从 Map 中取出对应的用户列表，并获取第一个元素 (即该用户对象)
                // 注意：这里假设 ID 是唯一的，所以直接取 get(0)，即每个Map的key只对应一个value
                user = userIdUserListMap.get(userId).get(0);
            }
            questionVO.setUserVO(userFeignClient.getUserVO(user));
            return questionVO;
        }).collect(Collectors.toList());
        questionVOPage.setRecords(questionVOList);
        return questionVOPage;
    }

    @Override
    public void incrSubmitNum(long questionId){
        UpdateWrapper<Question> updateWrapper = new UpdateWrapper<>();
        updateWrapper
                .setSql("submitNum = submitNum + 1")
                .eq("id", questionId);

        this.update(updateWrapper);
    }

    @Override
    public void incrAcceptedNum(long questionId) {
        UpdateWrapper<Question> updateWrapper = new UpdateWrapper<>();
        updateWrapper
                .setSql("acceptedNum = acceptedNum + 1")
                .eq("id", questionId);

        this.update(updateWrapper);
    }
}
