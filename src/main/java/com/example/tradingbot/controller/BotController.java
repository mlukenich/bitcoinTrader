package com.example.tradingbot.controller;

import com.example.tradingbot.service.BotStateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A simple REST controller to provide web endpoints for starting, stopping,
 * and checking the status of the trading bot.
 */
@RestController
public class BotController {

    private final BotStateService botStateService;

    public BotController(BotStateService botStateService) {
        this.botStateService = botStateService;
    }

    /**
     * Endpoint to start the bot.
     * @return A confirmation message.
     */
    @GetMapping("/start")
    public String startBot() {
        botStateService.start();
        return "Bot has been STARTED. Check the console for trading activity.";
    }

    /**
     * Endpoint to stop the bot.
     * @return A confirmation message.
     */
    @GetMapping("/stop")
    public String stopBot() {
        botStateService.stop();
        return "Bot has been STOPPED. No further trades will be executed.";
    }

    /**
     * Endpoint to get the current status of the bot.
     * @return The bot's current status ("RUNNING" or "STOPPED").
     */
    @GetMapping("/status")
    public String getStatus() {
        return "Bot status: " + botStateService.getStatus();
    }
}

