package ru.alfa.disbursement;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class Problems {
  static Map<String, Object> body(ApiException e, String requestId) {
    return Map.of(
        "type",
        "urn:alfa:problem:" + e.code.toLowerCase(Locale.ROOT).replace('_', '-'),
        "title",
        HttpStatus.valueOf(e.status).getReasonPhrase(),
        "status",
        e.status,
        "detail",
        e.getMessage(),
        "code",
        e.code,
        "requestId",
        requestId,
        "errors",
        e.errors);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> handle(Exception error, HttpServletRequest request) {
    ApiException e;
    if (error instanceof ApiException a) e = a;
    else if (error instanceof HttpMessageNotReadableException
        || error instanceof HttpMediaTypeNotSupportedException)
      e =
          new ApiException(
              400, "INVALID_JSON", "A single valid application/json body is required.");
    else if (error instanceof NoResourceFoundException || error instanceof NoHandlerFoundException)
      e = new ApiException(404, "NOT_FOUND", "Resource not found.");
    else if (error instanceof HttpRequestMethodNotSupportedException)
      e = new ApiException(400, "INVALID_METHOD", "Unsupported HTTP method.");
    else if (error instanceof DataAccessException || error instanceof TransactionException)
      e = ApiException.unavailable();
    else e = new ApiException(500, "INTERNAL_ERROR", "An unexpected error occurred.");
    request.setAttribute("problemCode", e.code);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    if (e.code.equals("IDEMPOTENCY_IN_PROGRESS")) headers.set("Retry-After", "1");
    return new ResponseEntity<>(
        body(e, (String) request.getAttribute("requestId")), headers, e.status);
  }
}
