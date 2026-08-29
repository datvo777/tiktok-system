package com.shortvideo.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI shortVideoOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Short Video Platform API")
                .version("v1")
                .description("Local MVP. Contracts are mirrored in contracts/openapi."));
    }
}
