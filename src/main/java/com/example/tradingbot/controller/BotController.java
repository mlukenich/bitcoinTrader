package com.example.tradingbot.controller;

import com.example.tradingbot.service.BotStateService;
import com.example.tradingbot.service.TradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * A simple REST controller to provide web endpoints for starting, stopping,
 * and checking the status of the trading bot.
 */
@RestController
public class BotController {

    private final BotStateService botStateService;
    private final TradingService tradingService;

    public BotController(BotStateService botStateService, TradingService tradingService) {
        this.botStateService = botStateService;
        this.tradingService = tradingService;
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

    /**
     * Endpoint to get the recent activity log.
     * @return A list of log messages.
     */
    @GetMapping("/activity") //
    public List<String> getActivity() {
        return tradingService.getActivityLog();
    }

    /**
     * Endpoint to get the last known price
     * @return last known bitcoin price
     */
    @GetMapping("/price")
    public Map<String, Object> getMarketData() {
        double price = tradingService.getLastKnownPrice();
        double rsi = tradingService.getLastKnownRsi(); // Get the RSI

        return Map.of(
                "price", price,
                "formattedPrice", String.format("$%,.2f", price),
                "rsi", rsi, // Add RSI to the response
                "formattedRsi", String.format("%.2f", rsi)
        );
    }

    @GetMapping("/account")
    public Map<String, Object> getAccount() {
        TradingService.AlpacaAccount account = tradingService.getAccountStatus();
        if (account != null) {
            double dailyGainLoss = account.getEquity() - account.getLastEquity();
            double dailyGainLossPercent = (dailyGainLoss / account.getLastEquity()) * 100;

            return Map.of(
                    "equity", String.format("$%,.2f", account.getEquity()),
                    "dailyGainLoss", String.format("%s$%,.2f", dailyGainLoss >= 0 ? "+" : "", dailyGainLoss),
                    "dailyGainLossPercent", String.format("(%.2f%%)", dailyGainLossPercent),
                    "gainLossColor", dailyGainLoss >= 0 ? "text-green-600" : "text-red-600"
            );
        }
        return Map.of(); // Return empty map on error
    }
}

