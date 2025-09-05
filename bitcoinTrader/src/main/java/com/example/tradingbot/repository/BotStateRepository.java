package com.example.tradingbot.repository;

import com.example.tradingbot.model.BotState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BotStateRepository extends JpaRepository<BotState, Long> {
}
