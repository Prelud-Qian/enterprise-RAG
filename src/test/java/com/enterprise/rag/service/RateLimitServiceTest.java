package com.enterprise.rag.service;

import com.enterprise.rag.common.BusinessException;
import com.enterprise.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimitServiceTest {

    @Test
    @DisplayName("滑动窗口限流：窗口内超限拒绝，关闭开关不限流")
    void 窗口限流() {
        RagProperties props = new RagProperties();
        props.getRateLimit().setAskPerMinute(3);
        RateLimitService service = new RateLimitService(props);

        service.checkAsk(1L);
        service.checkAsk(1L);
        service.checkAsk(1L);
        BusinessException e = assertThrows(BusinessException.class, () -> service.checkAsk(1L));
        assertEquals(429, e.getCode());
        // 其他用户不受影响
        service.checkAsk(2L);
        // 关闭开关后不限流
        props.getRateLimit().setEnabled(false);
        service.checkAsk(1L);
    }
}
