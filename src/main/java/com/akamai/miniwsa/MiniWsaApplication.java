package com.akamai.miniwsa;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@OpenAPIDefinition(info = @Info(
        title = "Mini WSA API",
        version = "0.1.0",
        description = "Security event ingestion, classification, threat scoring, summary statistics, samples and time-series analytics."))
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
