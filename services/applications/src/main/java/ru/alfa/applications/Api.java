package ru.alfa.applications;

import static ru.alfa.applications.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;

@RestController
public final class Api {
  private static final Logger LOG = LoggerFactory.getLogger(Api.class);

  private record Route(
      String method, Pattern pattern, List<String> parameters, JsonNode specification) {}

  private final Service service;
  private final List<Route> routes = new ArrayList<>();

  public Api(Service service) {
    this.service = service;
    Contract.OPENAPI
        .get("paths")
        .fields()
        .forEachRemaining(
            entry ->
                entry
                    .getValue()
                    .fields()
                    .forEachRemaining(
                        method -> {
                          if (!Set.of("get", "post", "patch").contains(method.getKey())) return;
                          List<String> names = new ArrayList<>();
                          Matcher matcher = Pattern.compile("\\{([^}]+)}").matcher(entry.getKey());
                          StringBuilder regex = new StringBuilder("^");
                          int offset = 0;
                          while (matcher.find()) {
                            regex
                                .append(
                                    Pattern.quote(
                                        entry.getKey().substring(offset, matcher.start())))
                                .append("([^/]+)");
                            names.add(matcher.group(1));
                            offset = matcher.end();
                          }
                          regex.append(Pattern.quote(entry.getKey().substring(offset))).append('$');
                          routes.add(
                              new Route(
                                  method.getKey().toUpperCase(Locale.ROOT),
                                  Pattern.compile(regex.toString()),
                                  names,
                                  method.getValue()));
                        }));
  }

  @RequestMapping("/**")
  public ResponseEntity<String> handle(HttpServletRequest request) {
    String requestId = request.getHeader("X-Request-Id");
    if (requestId == null) requestId = UUID.randomUUID().toString();
    try {
      try {
        Contract.validate("Uuid", node(requestId));
      } catch (Problem ex) {
        requestId = UUID.randomUUID().toString();
        throw new Problem(
            422,
            "VALIDATION_ERROR",
            "X-Request-Id must be a canonical UUID",
            arr(obj("path", "/headers/X-Request-Id", "message", "Invalid UUID")),
            0);
      }
      Route route = null;
      Map<String, String> params = new HashMap<>();
      for (Route candidate : routes) {
        var match = candidate.pattern.matcher(request.getRequestURI());
        if (candidate.method.equals(request.getMethod()) && match.matches()) {
          route = candidate;
          for (int i = 0; i < candidate.parameters.size(); i++)
            params.put(candidate.parameters.get(i), match.group(i + 1));
          break;
        }
      }
      if (route == null) throw new Problem(404, "NOT_FOUND", "HTTP operation was not found");
      String operation = s(route.specification, "operationId");
      if (operation.equals("applicationsHealthLive"))
        return reply(new Service.Reply(obj("status", "ok")), requestId);
      if (operation.equals("applicationsHealthReady")) {
        try {
          ready();
        } catch (DataAccessException | CannotCreateTransactionException ex) {
          throw Service.unavailable("Database schema or expected seed is unavailable");
        }
        return reply(new Service.Reply(obj("status", "ok")), requestId);
      }
      var actor = service.authenticate(request.getHeader("Authorization"));
      JsonNode securities =
          route.specification.has("security")
              ? route.specification.get("security")
              : Contract.OPENAPI.get("security");
      boolean allowed = false;
      String scheme =
          switch (actor.type()) {
            case "CUSTOMER" -> "CustomerBearer";
            case "STAFF" -> "StaffBearer";
            default -> null;
          };
      for (var security : securities) if (scheme != null && security.has(scheme)) allowed = true;
      Problem.require(allowed, 403, "FORBIDDEN", "This role cannot use this operation");
      Long version = null;
      for (var entry : params.entrySet()) {
        if (entry.getKey().equals("dataVersion")) {
          try {
            version = Long.valueOf(entry.getValue());
            if (version < 1 || !version.toString().equals(entry.getValue()))
              throw new NumberFormatException();
          } catch (NumberFormatException ex) {
            throw new Problem(422, "VALIDATION_ERROR", "dataVersion must be a positive integer");
          }
        } else Contract.validate("Uuid", node(entry.getValue()));
      }
      Service.Reply result;
      if (Set.of("POST", "PATCH").contains(request.getMethod())) {
        byte[] bytes;
        try {
          bytes = request.getInputStream().readNBytes(1024 * 1024 + 1);
        } catch (IOException ex) {
          throw new Problem(400, "MALFORMED_JSON", "Request body could not be read");
        }
        Problem.require(
            bytes.length <= 1024 * 1024, 422, "VALIDATION_ERROR", "Request body is too large");
        JsonNode body = null;
        if (bytes.length > 0) {
          Problem.require(
              request.getContentType() != null
                  && request.getContentType().split(";")[0].strip().equals("application/json"),
              422,
              "VALIDATION_ERROR",
              "Content-Type must be application/json");
          try {
            String text =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
            body = parse(text);
          } catch (IllegalArgumentException | CharacterCodingException ex) {
            throw new Problem(400, "MALFORMED_JSON", "Request contains invalid JSON");
          }
        }
        Map<String, String> headers = new HashMap<>();
        var names = request.getHeaderNames();
        while (names.hasMoreElements()) {
          String name = names.nextElement();
          headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        result =
            service.mutate(
                operation,
                request.getMethod(),
                request.getRequestURI(),
                params.get("applicationId"),
                actor,
                body,
                bytes.length > 0,
                headers,
                requestId);
      } else {
        Map<String, String> query = new HashMap<>();
        request
            .getParameterMap()
            .forEach(
                (key, values) -> {
                  Problem.require(
                      values.length == 1, 422, "VALIDATION_ERROR", "Repeated query parameter");
                  query.put(key, values[0]);
                });
        result =
            service.read(
                operation,
                params.get("applicationId"),
                params.get("companyId"),
                version,
                actor,
                query,
                requestId);
      }
      return reply(result, requestId);
    } catch (Problem ex) {
      return problem(ex, requestId);
    } catch (DataAccessException | CannotCreateTransactionException ex) {
      LOG.error("request_failed code=DATABASE_ERROR request_id={}", requestId);
      return problem(Service.unavailable("Database is temporarily unavailable"), requestId);
    } catch (RuntimeException ex) {
      LOG.error("request_failed code=INTERNAL_ERROR request_id={}", requestId);
      return problem(
          new Problem(500, "INTERNAL_ERROR", "Service is temporarily unavailable"), requestId);
    }
  }

  private ResponseEntity<String> reply(Service.Reply reply, String requestId) {
    var builder =
        ResponseEntity.status(reply.status())
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-Id", requestId);
    reply.headers().forEach(builder::header);
    return builder.body(canonical(reply.body()));
  }

  private ResponseEntity<String> problem(Problem problem, String requestId) {
    var builder =
        ResponseEntity.status(problem.status)
            .contentType(MediaType.valueOf("application/problem+json"))
            .header("X-Request-Id", requestId);
    if (problem.retryAfter > 0) builder.header("Retry-After", Long.toString(problem.retryAfter));
    return builder.body(canonical(problem.body(requestId)));
  }

  private void ready() {
    service.db.run(
        () -> {
          for (String table :
              List.of(
                  "applications",
                  "workflow_results",
                  "workflow_jobs",
                  "workflow_attempts",
                  "application_data_versions",
                  "application_timeline",
                  "information_requests",
                  "offer_acceptances",
                  "payout_instructions",
                  "idempotency_records")) service.db.rows("SELECT * FROM " + table + " LIMIT 0");
          var identity = service.db.one("SELECT current_user AS username,current_database() AS db");
          var seeds =
              service.db.one(
                  "SELECT count(*) AS n FROM customer_companies cc JOIN companies c ON"
                      + " c.id=cc.company_id WHERE"
                      + " cc.customer_id='00000000-0000-4000-8000-000000000001' AND c.id IN"
                      + " ('00000000-0000-4000-8000-000000000101','00000000-0000-4000-8000-000000000102','00000000-0000-4000-8000-000000000103','00000000-0000-4000-8000-000000000104','00000000-0000-4000-8000-000000000105')");
          if (!s(identity, "username").equals("applications_app")
              || !s(identity, "db").equals("alfa_applications")
              || n(seeds, "n") != 5)
            throw Service.unavailable("Database schema or expected seed is unavailable");
        });
  }
}
