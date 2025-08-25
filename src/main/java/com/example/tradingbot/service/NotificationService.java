package com.example.tradingbot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Handles sending email notifications.
 */
@Service
public class NotificationService {

    private final JavaMailSender mailSender;

    @Value("${notification.email.enabled}")
    private boolean emailEnabled;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${notification.recipient.email}")
    private String recipientEmail;

    // Using constructor injection for the JavaMailSender bean.
    public NotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Sends an email if no trades were executed in a 24-hour period.
     * @param reason A brief explanation of why no trades occurred.
     */
    public void sendNoTradeNotification(String reason) {
        if (!emailEnabled) {
            System.out.println("Email notifications are disabled in application.properties.");
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(recipientEmail);
            message.setSubject("Trading Bot Daily Update: No Trades Executed");
            message.setText("Hello,\n\nThis is an automated notification from your trading bot." +
                    "\n\nNo trades were executed in the last 24 hours." +
                    "\nReason: " + reason +
                    "\n\nThe bot is still running and monitoring the market." +
                    "\n\n- Your Friendly Bot");

            mailSender.send(message);
            System.out.println("Successfully sent 'no-trade' notification email to " + recipientEmail);
        } catch (Exception e) {
            // Log the error if the email fails to send.
            System.err.println("Error sending email notification: " + e.getMessage());
        }
    }
}

