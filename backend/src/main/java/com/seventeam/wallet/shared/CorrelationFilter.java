package com.seventeam.wallet.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Asigna identificadores de solicitud y correlacion para auditoria; no registra datos personales. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTR = "wallet.requestId";
    public static final String CORRELATION_ID_ATTR = "wallet.correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        String incoming = request.getHeader("X-Correlation-Id");
        String correlationId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : requestId;

        request.setAttribute(REQUEST_ID_ATTR, requestId);
        request.setAttribute(CORRELATION_ID_ATTR, correlationId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Correlation-Id", correlationId);
        MDC.put("requestId", requestId);
        MDC.put("correlationId", correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
            MDC.remove("correlationId");
        }
    }

    public static RequestContext contextOf(HttpServletRequest request) {
        Object requestId = request.getAttribute(REQUEST_ID_ATTR);
        Object correlationId = request.getAttribute(CORRELATION_ID_ATTR);
        return new RequestContext(
                requestId == null ? UUID.randomUUID().toString() : requestId.toString(),
                correlationId == null ? "n/a" : correlationId.toString());
    }
}
