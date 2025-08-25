package com.example.tradingbot.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the global state of the trading bot (e.g., running or stopped).
 * This service is thread-safe to handle concurrent requests from the web controller.
 */
@Service
public class BotStateService {

    // AtomicBoolean is used for thread-safe operations on a boolean value.
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    /**
     * Starts the trading bot's operation.
     */
    public void start() {
        isRunning.set(true);
        System.out.println("Trading Bot state has been set to: STARTED.");
    }

    /**
     * Stops the trading bot's operation.
     */
    public void stop() {
        isRunning.set(false);
        System.out.println("Trading Bot state has been set to: STOPPED.");
    }

    /**
     * Checks if the bot is currently running.
     * @return true if the bot is running, false otherwise.
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    /**
     * Gets the current status of the bot as a string.
     * @return "RUNNING" or "STOPPED".
     */
    public String getStatus() {
        return isRunning() ? "RUNNING" : "STOPPED";
    }
}

