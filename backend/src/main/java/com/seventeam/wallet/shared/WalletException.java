package com.seventeam.wallet.shared;

import java.util.List;

/** Error de negocio o validacion con codigo estable y mensaje seguro para mostrar al usuario. */
public class WalletException extends RuntimeException {

    public enum Kind { VALIDATION, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, UNPROCESSABLE }

    public record FieldIssue(String field, String message) {}

    private final Kind kind;
    private final String code;
    private final List<FieldIssue> fieldIssues;

    public WalletException(Kind kind, String code, String message) {
        this(kind, code, message, List.of());
    }

    public WalletException(Kind kind, String code, String message, List<FieldIssue> fieldIssues) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.fieldIssues = fieldIssues;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public List<FieldIssue> fieldIssues() {
        return fieldIssues;
    }
}
