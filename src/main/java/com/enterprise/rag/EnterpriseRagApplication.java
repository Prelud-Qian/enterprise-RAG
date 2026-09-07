package com.enterprise.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan   // 扫描 RagProperties 等 @ConfigurationProperties 配置类
@MapperScan("com.enterprise.rag.dao.mapper")
public class EnterpriseRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnterpriseRagApplication.class, args);
    }
}
