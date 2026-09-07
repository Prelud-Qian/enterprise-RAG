package com.enterprise.rag.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * 双数据源装配（业务 MySQL + 向量 PG）：
 * - 主数据源（MySQL，@Primary）绑定 spring.datasource.*，MyBatis-Plus 自动使用；
 *   必须手动声明——否则 pgDataSource 先于 Boot 自动配置注册，会让
 *   DataSourceAutoConfiguration 因 @ConditionalOnMissingBean 退避，导致
 *   整个应用只有一个 PG 数据源，MP 的 SQL 全部打到 PG 上（运行时才爆）。
 *   另外 spring.datasource.url 无法直接绑到 HikariDataSource（setter 叫
 *   jdbcUrl），必须经 DataSourceProperties 中转
 * - PG 使用自定义前缀 pgvector.* 手建 Hikari 连接池，业务代码通过
 *   @Qualifier("pgJdbcTemplate") 注入使用
 */
@Configuration
public class PgVectorDataSourceConfig {

    /** 主数据源属性（spring.datasource.*） */
    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties mysqlDataSourceProperties() {
        return new DataSourceProperties();
    }

    /** 主数据源 MySQL（@Primary：MyBatis-Plus 默认绑定） */
    @Bean
    @Primary
    public DataSource mysqlDataSource(DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties(prefix = "pgvector.datasource")
    public PgVectorDataSourceProperties pgVectorDataSourceProperties() {
        return new PgVectorDataSourceProperties();
    }

    @Bean(name = "pgDataSource", destroyMethod = "close")
    public DataSource pgDataSource(PgVectorDataSourceProperties props) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName(props.getDriverClassName());
        config.setJdbcUrl(props.getJdbcUrl());
        config.setUsername(props.getUsername());
        config.setPassword(props.getPassword());
        config.setPoolName("pgvector-pool");
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }

    @Bean(name = "pgJdbcTemplate")
    public NamedParameterJdbcTemplate pgJdbcTemplate(@Qualifier("pgDataSource") DataSource ds) {
        return new NamedParameterJdbcTemplate(ds);
    }
}
