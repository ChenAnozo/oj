package com.yupi.yuojbackendquestionservice.manager;

import com.yupi.yuojbackendmodel.entity.Question;
import com.yupi.yuojbackendquestionservice.service.QuestionService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class QuestionCacheManager {

    private static final String CACHE_KEY_PREFIX = "question:detail:";
    private static final String LOCK_KEY_PREFIX = "lock:question:";

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private QuestionService questionService;

    @Value("${oj.cache.question-ttl-seconds:300}")
    private long questionTtlSeconds;

    @Value("${oj.cache.null-ttl-seconds:60}")
    private long nullTtlSeconds;

    public Question getQuestionById(long id) {
        String cacheKey = CACHE_KEY_PREFIX + id;
        try {
            RBucket<Question> bucket = redissonClient.getBucket(cacheKey);
            Question cached = bucket.get();
            if (cached != null) {
                return cached.getId() != null ? cached : null;
            }

            RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + id);
            boolean locked = lock.tryLock(2, 10, TimeUnit.SECONDS);
            if (!locked) {
                Thread.sleep(50);
                Question again = redissonClient.<Question>getBucket(cacheKey).get();
                if (again != null) {
                    return again.getId() != null ? again : null;
                }
                return questionService.getById(id);
            }
            try {
                Question recheck = redissonClient.<Question>getBucket(cacheKey).get();
                if (recheck != null) {
                    return recheck.getId() != null ? recheck : null;
                }
                Question question = questionService.getById(id);
                if (question == null) {
                    redissonClient.<Question>getBucket(cacheKey)
                            .set(new Question(), nullTtlSeconds, TimeUnit.SECONDS);
                    return null;
                }
                long ttl = questionTtlSeconds + ThreadLocalRandom.current().nextInt(60);
                redissonClient.<Question>getBucket(cacheKey)
                        .set(question, ttl, TimeUnit.SECONDS);
                return question;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return questionService.getById(id);
        } catch (Exception e) {
            log.warn("题目缓存异常，降级查库 id={}", id, e);
            return questionService.getById(id);
        }
    }

    public void invalidate(long id) {
        try {
            redissonClient.getBucket(CACHE_KEY_PREFIX + id).delete();
        } catch (Exception e) {
            log.warn("题目缓存删除失败 id={}", id, e);
        }
    }
}
