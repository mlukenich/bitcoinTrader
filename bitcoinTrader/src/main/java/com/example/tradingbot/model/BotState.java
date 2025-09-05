package com.example.tradingbot.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
public class BotState {

    @Id
    private Long id = 1L; // We will only ever have one row, with a fixed ID of 1

    private boolean inPosition = false;
    private double purchasePrice = 0.0;
    private double highestPriceSinceBuy = 0.0;
    private double lastKnownPrice = 0.0;
    private double lastKnownRsi = 0.0;
    private double previousShortMA = 0.0;
    private double previousLongMA = 0.0;

    @ElementCollection(fetch = FetchType.EAGER) // JPA will manage this list in a separate table
    @CollectionTable(name = "price_history", joinColumns = @JoinColumn(name = "bot_state_id"))
    @Column(name = "price")
    private List<Double> priceHistory = new ArrayList<>();
}
