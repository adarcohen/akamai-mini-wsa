package com.akamai.miniwsa;

import java.time.Clock;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(info = @Info(
        title = "Mini WSA API",
        version = "0.1.0",
        description = "Security event ingestion and analytics. Currently supports durable event ingestion."))
@SpringBootApplication
public class MiniWsaApplication {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(MiniWsaApplication.class, args);
    }
}
