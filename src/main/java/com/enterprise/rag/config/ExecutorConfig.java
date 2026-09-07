package com.enterprise.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文档处理线程池（rag.upload.async=true 时生效）：
 * 解析/向量化是 IO 密集型任务，池大小 2~4；
 * 队列满时 CallerRunsPolicy 降级为调用线程同步执行，保证任务不丢
 */
@Configuration
public class ExecutorConfig {

    @Bean(name = "ragUploadExecutor", destroyMethod = "shutdown")
    public ExecutorService ragUploadExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threadFactory = r -> new Thread(r, "rag-upload-" + counter.incrementAndGet());
        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50), threadFactory, new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
