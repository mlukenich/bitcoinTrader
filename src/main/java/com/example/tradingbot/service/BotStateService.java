package com.example.tradingbot.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the global state of the trading bot (e.g., running or stopped).
 * This service is thread-safe to handle concurrent requests from the web controller.
 */
@Service
public class BotStateService {

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    // Use AtomicReference for a thread-safe, updatable status message
    private final AtomicReference<String> statusMessage = new AtomicReference<>("STOPPED");

    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            setStatusMessage("STARTED");
        }
    }

    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            setStatusMessage("STOPPED");
        }
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public String getStatus() {
        return statusMessage.get();
    }

    public void setStatusMessage(String message) {
        // Only update the message if the bot is considered running.
        if (isRunning.get()) {
            this.statusMessage.set(message);
        }
    }
}

