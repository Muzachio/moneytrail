package io.github.muzachio.moneytrail.shared.idempotency;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * Keeps the raw body of idempotent requests for their fingerprint. By the time {@link
 * IdempotentRequests} runs, Spring MVC has read the body to build the controller's arguments, so it
 * is read here first, kept as a request attribute and handed on to the JSON parser unchanged. A
 * request without a body never gets here, and its fingerprint covers an empty body.
 */
@ControllerAdvice
class IdempotentRequestBodies extends RequestBodyAdviceAdapter {

    @Override
    public boolean supports(
            MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        Method method = parameter.getMethod();
        return method != null && IdempotentEndpoints.takesTheHeader(method);
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage message,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        byte[] body = message.getBody().readAllBytes();
        RequestContextHolder.currentRequestAttributes()
                .setAttribute(IdempotentRequests.BODY_ATTRIBUTE, body, RequestAttributes.SCOPE_REQUEST);
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return message.getHeaders();
            }
        };
    }
}
