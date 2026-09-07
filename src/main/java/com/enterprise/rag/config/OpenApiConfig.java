package com.enterprise.rag.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger API 文档：启动后访问 http://localhost:8080/swagger-ui.html，
 * 右上角 Authorize 填入登录返回的 JWT 即可在线调试所有接口
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("enterprise-RAG API")
                        .description("企业知识库 RAG 问答系统：认证 / 知识库 / 文档上传 / 混合检索问答 / 审计日志")
                        .version("1.0.0"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
