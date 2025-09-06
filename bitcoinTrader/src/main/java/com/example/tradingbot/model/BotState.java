package com.example.tradingbot.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the persistent state of the trading bot.
 * This entity is stored in the database to maintain state across application restarts.
 */
@Entity
@Data
public class BotState {

    /**
     * The fixed ID for the single bot state entry in the database.
     */
    @Id
    private Long id = 1L;

    /**
     * Flag indicating if the bot is currently holding a position.
     */
    private boolean inPosition = false;

    /**
     * The price at which the current position was purchased.
     */
    private double purchasePrice = 0.0;

    /**
     * The highest price reached since the current position was purchased.
     */
    private double highestPriceSinceBuy = 0.0;

    /**
     * The last known price of the asset.
     */
    private double lastKnownPrice = 0.0;

    /**
     * The last calculated RSI value.
     */
    private double lastKnownRsi = 0.0;

    /**
     * The previous short moving average value.
     */
    private double previousShortMA = 0.0;

    /**
     * The previous long moving average value.
     */
    private double previousLongMA = 0.0;

    /**
     * A list of recent prices used for calculating indicators.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "price_history", joinColumns = @JoinColumn(name = "bot_state_id"))
    @Column(name = "price")
    private List<Double> priceHistory = new ArrayList<>();

    /**
     * A list of recent short moving average values for charting.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "short_ma_history", joinColumns = @JoinColumn(name = "bot_state_id"))
    @Column(name = "short_ma")
    private List<Double> shortMaHistory = new ArrayList<>();

    /**
     * A list of recent long moving average values for charting.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "long_ma_history", joinColumns = @JoinColumn(name = "bot_state_id"))
    @Column(name = "long_ma")
    private List<Double> longMaHistory = new ArrayList<>();
}
