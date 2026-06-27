package com.example.dispute.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI disputeOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Order Fulfillment Dispute API")
                                .version("v1")
                                .description(
                                        "Human-review-gated dispute workflow and deterministic execution API."));
    }
}
