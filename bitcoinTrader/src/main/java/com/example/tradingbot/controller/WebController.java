package com.example.tradingbot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * This controller handles requests for the web user interface.
 * It uses @Controller, so it returns view templates instead of raw data.
 */
@Controller
public class WebController {

    /**
     * This method handles requests to the root URL (e.g., http://localhost:8080/)
     * and returns the name of the HTML template to display, which is "index".
     *
     * @return The name of the view template.
     */
    @GetMapping("/")
    public String index() {
        return "index"; // This tells Spring to render src/main/resources/templates/index.html
    }
}
