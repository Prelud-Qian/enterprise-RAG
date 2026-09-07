package com.enterprise.rag.config;

import lombok.Data;

/**
 * pgvector 数据源属性（application.yml 前缀 pgvector.datasource）
 * 使用独立前缀，避免与 Boot 主数据源自动配置冲突
 */
@Data
public class PgVectorDataSourceProperties {

    private String driverClassName;
    private String jdbcUrl;
    private String username;
    private String password;
}
