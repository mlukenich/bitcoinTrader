package com.example.tradingbot.scheduler;

import com.example.tradingbot.service.BotStateService;
import com.example.tradingbot.service.TradingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * This component contains the scheduled tasks that drive the bot's core loop.
 */
@Component
public class TradingScheduler {

    private final BotStateService botStateService;
    private final TradingService tradingService;

    public TradingScheduler(BotStateService botStateService, TradingService tradingService) {
        this.botStateService = botStateService;
        this.tradingService = tradingService;
    }

    /**
     * Runs the trading strategy check at a fixed rate defined in application.properties.
     * The task will only execute if the bot's state is "RUNNING".
     */
    @Scheduled(fixedRateString = "${scheduler.check-interval-ms}")
    public void runTradingStrategy() {
        if (botStateService.isRunning()) {
            System.out.println("\n--- [Scheduler] Running Trading Check ---");
            try {
                tradingService.executeStrategy();
            } catch (Exception e) {
                System.err.println("!! An error occurred during the trading strategy execution: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            // If the bot is stopped, this method does nothing.
            // We can add a log here for debugging if needed, but it's often better to keep it quiet.
        }
    }

    /**
     * TODO: Implement the 24-hour notification check.
     * This would run once a day, check the timestamp of the last trade,
     * and call the NotificationService if no trade has occurred.
     */
    // @Scheduled(cron = "0 0 8 * * ?") // Example: Run every day at 8 AM
    // public void checkForDailyTrades() {
    //     if (botStateService.isRunning()) {
    //         // Logic to check last trade time and send notification
    //     }
    // }
}

