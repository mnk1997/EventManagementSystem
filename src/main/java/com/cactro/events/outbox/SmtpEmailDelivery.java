package com.cactro.events.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpEmailDelivery implements EmailDelivery {
    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailDelivery(JavaMailSender mailSender, @Value("${app.email.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendBookingConfirmation(EmailPayload payload) {
        String text = "Your booking is confirmed.\n\n" +
                "Event: " + payload.eventName() + "\n" +
                "Starts: " + payload.eventStartsAt() + "\n" +
                "Venue: " + payload.venue() + ", " + payload.location() + "\n" +
                "Tickets: " + payload.quantity() + "\n" +
                "Booking reference: " + payload.bookingId() + "\n";
        send(payload.recipient(), "Booking confirmed: " + payload.eventName(), text);
    }

    @Override
    public void sendEventUpdated(EmailPayload payload) {
        String text = "An event you booked has been updated.\n\n" +
                "Event: " + payload.eventName() + "\n" +
                "Starts: " + payload.eventStartsAt() + "\n" +
                "Venue: " + payload.venue() + ", " + payload.location() + "\n";
        send(payload.recipient(), "Event updated: " + payload.eventName(), text);
    }

    private void send(String recipient, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
