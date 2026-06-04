package com.example.teacherassistantai.integration.email;

import com.example.teacherassistantai.exception.ExternalServiceException;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.sendgrid.helpers.mail.objects.Personalization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {
    private static final int SENDGRID_ACCEPTED_STATUS = 202;

    private final SendGrid sendGrid;
    private final SpringTemplateEngine templateEngine;

    @Value("${application.mailing.sendgrid.from-email}")
    private String emailFrom;
    @Value("${application.mailing.sendgrid.from-name:Teacher Assistant AI}")
    private String emailFromName;

    public void sendEmail(
            String to,
            String username,
            EmailTemplateName emailTemplateName,
            String confirmationUrl,
            String activationCode,
            String subject) {
        String templateName;
        if (emailTemplateName == null){
            templateName = "confirm-email";
        }
        else {
            templateName = emailTemplateName.getTemplateName();
        }

        Map<String, Object> properties = Map.of("username", username,
                "confirmationUrl", confirmationUrl,
                "activationCode", activationCode);

        Context context = new Context();
        context.setVariables(properties);

        String template = templateEngine.process(templateName, context);
        Mail mail = new Mail();
        mail.setFrom(new Email(emailFrom, emailFromName));
        mail.setSubject(subject);
        mail.addContent(new Content("text/html", template));

        Personalization personalization = new Personalization();
        int recipientCount = 0;
        for (String recipient : to.split(",")) {
            String trimmedRecipient = recipient.strip();
            if (!trimmedRecipient.isBlank()) {
                personalization.addTo(new Email(trimmedRecipient));
                recipientCount++;
            }
        }
        if (recipientCount == 0) {
            throw new IllegalArgumentException("Email recipient must not be blank");
        }
        mail.addPersonalization(personalization);

        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);
            if (response.getStatusCode() != SENDGRID_ACCEPTED_STATUS) {
                log.warn("SendGrid email request failed, status={}, body={}", response.getStatusCode(), response.getBody());
                throw new ExternalServiceException("Failed to send email via SendGrid");
            }
            log.info("Email sent successfully via SendGrid");
        } catch (IOException e) {
            throw new ExternalServiceException("Failed to send email via SendGrid", e);
        }
    }
}
