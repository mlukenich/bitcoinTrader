package com.example.tradingbot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * A service dedicated to sending email notifications.
 */
@Service
public class NotificationService {

    private final JavaMailSender mailSender;
    private final String recipientEmail;
    private final String senderEmail;

    /**
     * Constructs the service, injecting the JavaMailSender and necessary properties.
     * Spring Boot automatically configures the JavaMailSender bean based on your
     * application.properties.
     */
    public NotificationService(JavaMailSender mailSender,
                               @Value("${notification.recipient.email}") String recipientEmail,
                               @Value("${spring.mail.username}") String senderEmail) {
        this.mailSender = mailSender;
        this.recipientEmail = recipientEmail;
        this.senderEmail = senderEmail;
    }

    /**
     * Sends a simple text email.
     * @param subject The subject line of the email.
     * @param body The body text of the email.
     */
    public void sendTradeNotification(String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(senderEmail);
            message.setTo(recipientEmail);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            System.out.println("Email notification sent successfully.");
        } catch (Exception e) {
            System.err.println("Error sending email notification: " + e.getMessage());
        }
    }
}

