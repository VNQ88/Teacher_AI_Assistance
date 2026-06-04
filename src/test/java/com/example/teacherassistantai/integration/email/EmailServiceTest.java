package com.example.teacherassistantai.integration.email;

import com.example.teacherassistantai.exception.ExternalServiceException;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private SendGrid sendGrid;
    @Mock
    private SpringTemplateEngine templateEngine;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(sendGrid, templateEngine);
        ReflectionTestUtils.setField(emailService, "emailFrom", "no-reply@example.com");
        ReflectionTestUtils.setField(emailService, "emailFromName", "Teacher Assistant AI");
    }

    @Test
    void sendEmail_shouldSendHtmlEmailViaSendGrid() throws IOException {
        when(templateEngine.process(eq("activate-account"), any(Context.class)))
                .thenReturn("<html>123456</html>");
        when(sendGrid.api(any(Request.class))).thenReturn(new Response(202, "", Map.of()));

        emailService.sendEmail(
                "student@mail.com, teacher@mail.com",
                "Student",
                EmailTemplateName.ACTIVATE_ACCOUNT,
                "https://app.example.com/activate",
                "123456",
                "Activate your account"
        );

        ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("activate-account"), contextCaptor.capture());
        Context context = contextCaptor.getValue();
        assertEquals("Student", context.getVariable("username"));
        assertEquals("https://app.example.com/activate", context.getVariable("confirmationUrl"));
        assertEquals("123456", context.getVariable("activationCode"));

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(sendGrid).api(requestCaptor.capture());
        Request request = requestCaptor.getValue();
        assertEquals(Method.POST, request.getMethod());
        assertEquals("mail/send", request.getEndpoint());
        assertTrue(request.getBody().contains("\"email\":\"no-reply@example.com\""));
        assertTrue(request.getBody().contains("\"name\":\"Teacher Assistant AI\""));
        assertTrue(request.getBody().contains("\"subject\":\"Activate your account\""));
        assertTrue(request.getBody().contains("\"email\":\"student@mail.com\""));
        assertTrue(request.getBody().contains("\"email\":\"teacher@mail.com\""));
        assertTrue(request.getBody().contains("\"type\":\"text/html\""));
        assertTrue(request.getBody().contains("<html>123456</html>"));
    }

    @Test
    void sendEmail_shouldThrowExternalServiceExceptionWhenSendGridRejectsRequest() throws IOException {
        when(templateEngine.process(eq("reset-password"), any(Context.class)))
                .thenReturn("<html>reset</html>");
        when(sendGrid.api(any(Request.class))).thenReturn(new Response(400, "bad request", Map.of()));

        ExternalServiceException exception = assertThrows(
                ExternalServiceException.class,
                () -> emailService.sendEmail(
                        "student@mail.com",
                        "Student",
                        EmailTemplateName.RESET_PASSWORD,
                        "https://app.example.com/activate",
                        "123456",
                        "Reset your password"
                )
        );

        assertEquals("Failed to send email via SendGrid", exception.getMessage());
    }

    @Test
    void sendEmail_shouldThrowExternalServiceExceptionWhenSendGridCallFails() throws IOException {
        when(templateEngine.process(eq("reset-password"), any(Context.class)))
                .thenReturn("<html>reset</html>");
        when(sendGrid.api(any(Request.class))).thenThrow(new IOException("network"));

        ExternalServiceException exception = assertThrows(
                ExternalServiceException.class,
                () -> emailService.sendEmail(
                        "student@mail.com",
                        "Student",
                        EmailTemplateName.RESET_PASSWORD,
                        "https://app.example.com/activate",
                        "123456",
                        "Reset your password"
                )
        );

        assertEquals("Failed to send email via SendGrid", exception.getMessage());
    }
}
