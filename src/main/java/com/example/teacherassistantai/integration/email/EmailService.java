package com.example.teacherassistantai.integration.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender javaMailSender;
    private final SpringTemplateEngine templateEngine;

    @Value("${spring.mail.from}")
    private String emailFrom;
    @Async
    public void sendEmail(
            String to,
            String username,
            EmailTemplateName emailTemplateName,
            String confirmationUrl,
            String activationCode,
            String subject) throws MessagingException {
        // send email
        String templateName;
        if (emailTemplateName == null){
            templateName = "confirm-email";
        }
        else {
            templateName = emailTemplateName.getTemplateName();
        }

        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage,
                MimeMessageHelper.MULTIPART_MODE_MIXED,
                StandardCharsets.UTF_8.name());
        Map<String, Object> properties = Map.of("username", username,
                "confirmationUrl", confirmationUrl,
                "activationCode", activationCode);

        Context context = new Context();
        context.setVariables(properties);

        helper.setFrom(emailFrom);

        if (to.contains(",")) {
            // If multiple recipients are provided, split them by comma
            helper.setTo(InternetAddress.parse(to));
        } else {
            helper.setTo(to);
        }
        helper.setSubject(subject);

        String template = templateEngine.process(templateName, context);
        helper.setText(template, true);

        javaMailSender.send(mimeMessage);
        log.info("Email sent successfully");
    }
}
