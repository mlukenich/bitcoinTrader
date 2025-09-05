package com.example.tradingbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
// Notice the unused imports for Bean and RestTemplate can now be removed.

@SpringBootApplication
@EnableScheduling
public class TradingBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingBotApplication.class, args);
    }

    // The duplicate bean method has been removed.
    // Now, only AppConfig.java is responsible for creating the RestTemplate.
}

