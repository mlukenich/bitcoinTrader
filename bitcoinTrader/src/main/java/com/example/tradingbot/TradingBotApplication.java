package com.example.tradingbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * Main entry point for the Spring Boot Trading Bot application.
 * The @EnableScheduling annotation is crucial for activating the
 * scheduled tasks that run the trading logic.
 */
@SpringBootApplication
@EnableScheduling
public class TradingBotApplication {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    public static void main(String[] args) {
        SpringApplication.run(TradingBotApplication.class, args);
    }

}

