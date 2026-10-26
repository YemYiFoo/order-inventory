package dev.portfolio.orders;

/** Carries an intentional HTTP business error without database details. */
public class ApiException extends RuntimeException {

    final int status;

    final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static ApiException invalid(String message) {
        return new ApiException(400, "INVALID_REQUEST", message);
    }

    static ApiException missing() {
        return new ApiException(404, "NOT_FOUND", "Resource not found");
    }
}
