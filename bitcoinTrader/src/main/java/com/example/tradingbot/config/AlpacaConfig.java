package com.example.tradingbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

@Configuration
@ConfigurationProperties(prefix = "alpaca")
@Data
public class AlpacaConfig {

    private String apiKey;
    private String apiSecret;
    private String baseUrl;
    private String dataUrl;

}
