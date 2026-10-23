package com.seventeam.wallet.shared.web;

import com.seventeam.wallet.shared.CorrelationFilter;
import com.seventeam.wallet.shared.WalletException;
import com.seventeam.wallet.shared.WalletException.FieldIssue;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Errores consistentes: sin stack traces, SQL ni detalles internos. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WalletException.class)
    ResponseEntity<ApiError> handleWallet(WalletException e, HttpServletRequest request) {
        return respond(statusOf(e.kind()), e.code(), e.getMessage(), e.fieldIssues(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<FieldIssue> issues = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldIssue(f.getField(), f.getDefaultMessage()))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Revisa los datos ingresados y corrígelos para continuar.", issues, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> handleUnreadable(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "La solicitud no tiene el formato esperado.", List.of(), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        String code = "Idempotency-Key".equalsIgnoreCase(e.getHeaderName())
                ? "IDEMPOTENCY_KEY_REQUIRED" : "MALFORMED_REQUEST";
        return respond(HttpStatus.BAD_REQUEST, code, "Falta un encabezado requerido.", List.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recurso no encontrado.", List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Error inesperado en la solicitud {}", request.getAttribute(CorrelationFilter.REQUEST_ID_ATTR), e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Ocurrió un error inesperado. Inténtalo de nuevo.", List.of(), request);
    }

    private static HttpStatus statusOf(WalletException.Kind kind) {
        return switch (kind) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String code, String message,
                                                    List<FieldIssue> issues, HttpServletRequest request) {
        Object requestId = request.getAttribute(CorrelationFilter.REQUEST_ID_ATTR);
        ApiError body = new ApiError(status.value(), code, message, issues.isEmpty() ? null : issues,
                requestId == null ? null : requestId.toString());
        return ResponseEntity.status(status).body(body);
    }
}
