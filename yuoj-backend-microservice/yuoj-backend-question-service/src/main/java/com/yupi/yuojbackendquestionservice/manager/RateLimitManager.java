package com.yupi.yuojbackendquestionservice.manager;

import com.yupi.yuojbackendcommon.common.ErrorCode;
import com.yupi.yuojbackendcommon.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RateLimitManager {

    @Resource
    private RedissonClient redissonClient;

    @Value("${oj.ratelimit.cooldown-seconds:5}")
    private long cooldownSeconds;

    @Value("${oj.ratelimit.minute-count:10}")
    private long minuteCount;

    @Value("${oj.ratelimit.hour-count:100}")
    private long hourCount;

    @Value("${oj.ratelimit.day-count:500}")
    private long dayCount;

    public void checkSubmitLimit(long userId) {
        try {
            RLock cooldownLock = redissonClient.getLock("submit:cooldown:" + userId);

            //waittime等待多久获取锁, leasetime最多持有多久锁
            boolean got = cooldownLock.tryLock(0, cooldownSeconds, TimeUnit.SECONDS);
            if (!got) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, "操作过于频繁，请稍后再试");
            }

            acquireWindow("submit:rate:minute:" + userId, minuteCount, 1, RateIntervalUnit.MINUTES);
            acquireWindow("submit:rate:hour:" + userId, hourCount, 1, RateIntervalUnit.HOURS);
            acquireWindow("submit:rate:day:" + userId, dayCount, 1, RateIntervalUnit.DAYS);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("限流组件异常，降级放行 userId={}", userId, e);
        }
    }

    private void acquireWindow(String key, long count, long interval, RateIntervalUnit unit) {
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        //count:规定时间内的请求数
        //interval * unit: 规定时间
        limiter.trySetRate(RateType.OVERALL, count, interval, unit);
        if (!limiter.tryAcquire(1)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, "提交过于频繁，请稍后再试");
        }
    }
}
