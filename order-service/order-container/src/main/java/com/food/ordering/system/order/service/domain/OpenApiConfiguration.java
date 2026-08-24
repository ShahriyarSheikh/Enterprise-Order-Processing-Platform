package com.food.ordering.system.order.service.domain;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI orderServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Enterprise Order Processing Platform - Order API")
                        .version("v1")
                        .description("Create orders and retrieve their current status by tracking ID."));
    }
}
