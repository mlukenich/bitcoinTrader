package com.example.tradingbot.controller;

import com.example.tradingbot.service.BotStateService;
import com.example.tradingbot.service.TradingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    private static final Logger logger = LoggerFactory.getLogger(BotController.class);


    public BotController(BotStateService botStateService, TradingService tradingService) {
        this.botStateService = botStateService;
        this.tradingService = tradingService;
    }

    /**
     * Endpoint to start the bot.
     *
     * @return A confirmation message.
     */
    @PostMapping("/api/start")
    public String startBot() {
        botStateService.start();
        logger.info("Bot has been STARTED.");
        tradingService.addLogEntry("Bot has been STARTED.");
        return "Bot has been STARTED. Check the console for trading activity.";
    }

    /**
     * Endpoint to stop the bot.
     *
     * @return A confirmation message.
     */
    @PostMapping("/api/stop")
    public String stopBot() {
        botStateService.stop();
        logger.info("Bot has been STOPPED.");
        tradingService.addLogEntry("Bot has been STOPPED.");
        return "Bot has been STOPPED. No further trades will be executed.";
    }

    /**
     * Endpoint to get the current status of the bot.
     *
     * @return The bot's current status ("RUNNING" or "STOPPED").
     */
    @GetMapping("/status")
    public String getStatus() {
        return "Bot status: " + botStateService.getStatus();
    }

    /**
     * Endpoint to get the recent activity log.
     *
     * @return A list of log messages.
     */
    @GetMapping("/activity") //
    public List<TradingService.LogEntry> getActivity() {
        return tradingService.getActivityLog();
    }

    /**
     * Endpoint to get the last known price
     *
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

    /**
     * Endpoint to get account information.
     *
     * @return A map containing account equity and position P/L.
     */
    @GetMapping("/account")
    public Map<String, Object> getAccount() {
        // Fetch total account equity
        TradingService.AlpacaAccount account = tradingService.getAccountStatus();

        // Fetch P/L for the specific Bitcoin position
        Map<String, Object> positionPl = tradingService.getBtcPositionPl();
        double unrealizedPl = (double) positionPl.get("unrealizedPl");
        boolean isPositive = (boolean) positionPl.get("isPositive");

        if (account != null) {
            return Map.of(
                    "equity", String.format("$%,.2f", account.getEquity()),
                    "positionGainLoss", String.format("%s$%,.2f", unrealizedPl >= 0 ? "+" : "", unrealizedPl),
                    "gainLossColor", isPositive ? "text-green-300" : "text-red-400" // Colors for dark theme
            );
        }
        return Map.of(); // Return empty map on error
    }

    @GetMapping("/api/state")
    public Map<String, Object> getState() {
        return tradingService.getFullUpdate();
    }

    /**
     * Endpoint to get chart data.
     *
     * @return A map containing data for the chart.
     */
    @GetMapping("/api/chart-data")
    public Map<String, List<?>> getChartData() {
        return tradingService.getChartData();
    }
}