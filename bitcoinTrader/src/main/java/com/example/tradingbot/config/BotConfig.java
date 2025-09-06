package com.example.tradingbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "bot")
@Data
public class BotConfig {
    private String symbol;
    private int shortMaPeriod;
    private int longMaPeriod;
    private double riskPercentage;
    private int rsiPeriod;
}
