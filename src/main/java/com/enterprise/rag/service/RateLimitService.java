package com.enterprise.rag.service;

import com.enterprise.rag.common.BusinessException;
import com.enterprise.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户级接口限流（滑动窗口，内存计数）：
 * /ask 与 /search 每次请求会消耗 2~3 次 LLM 调用（改写/精排），
 * key 泄露或被刷会直接烧钱，因此按用户限制每分钟请求数。
 * 分布式部署需换成 Redis 计数器（当前单实例内存实现够用）
 */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RagProperties props;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public void checkAsk(Long userId) {
        check(userId, "ask", props.getRateLimit().getAskPerMinute());
    }

    public void checkSearch(Long userId) {
        check(userId, "search", props.getRateLimit().getSearchPerMinute());
    }

    private void check(Long userId, String key, int limit) {
        if (!props.getRateLimit().getEnabled()) {
            return;
        }
        Deque<Long> times = windows.computeIfAbsent(key + ":" + userId, x -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (times) {
            // 滑动窗口：淘汰 1 分钟前的记录
            while (!times.isEmpty() && now - times.peekFirst() > 60_000L) {
                times.pollFirst();
            }
            if (times.size() >= limit) {
                throw new BusinessException(429, "请求过于频繁，请稍后再试");
            }
            times.addLast(now);
        }
    }
}
