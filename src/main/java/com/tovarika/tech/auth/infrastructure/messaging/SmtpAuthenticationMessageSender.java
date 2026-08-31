package com.tovarika.tech.auth.infrastructure.messaging;

import com.tovarika.tech.auth.application.AuthenticationProperties;
import com.tovarika.tech.auth.application.port.AuthenticationMessageSender;
import java.net.URI;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class SmtpAuthenticationMessageSender implements AuthenticationMessageSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpAuthenticationMessageSender.class);

    private final JavaMailSender mailSender;
    private final AuthenticationProperties properties;

    public SmtpAuthenticationMessageSender(JavaMailSender mailSender, AuthenticationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendPasswordReset(String email, String rawToken) {
        URI link = UriComponentsBuilder.fromUri(properties.mail().uiBaseUri())
                .path("/auth/reset-password")
                .queryParam("token", rawToken)
                .build()
                .encode()
                .toUri();
        send(email, "Reset your Tovarika password", "Open this link to reset your password: " + link);
    }

    private void send(String email, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mail().from());
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException deliveryFailure) {
            // Public enumeration-safe commands must keep the same response. Never log address, body or token.
            log.error("Authentication mail delivery failed type={}", deliveryFailure.getClass().getName());
        }
    }
}
