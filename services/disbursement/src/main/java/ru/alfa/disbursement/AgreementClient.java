package ru.alfa.disbursement;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class AgreementClient {
  private final Contracts contracts;
  private final HttpClient http;
  private final URI base;
  private final String token;
  private final Duration timeout;

  AgreementClient(Contracts contracts, Environment env) {
    this.contracts = contracts;
    base = URI.create(env.getRequiredProperty("AGREEMENTS_URL").replaceAll("/+$", ""));
    if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null)
      throw new IllegalArgumentException(
          "AGREEMENTS_URL must be an HTTP base URL without credentials/query/fragment");
    token = env.getRequiredProperty("INTERNAL_API_TOKEN");
    int millis = env.getProperty("AGREEMENTS_TIMEOUT_MS", Integer.class, 2000);
    if (millis < 1 || millis > 30000)
      throw new IllegalArgumentException("AGREEMENTS_TIMEOUT_MS must be 1–30000");
    timeout = Duration.ofMillis(millis);
    http =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  JsonNode signed(JsonNode command, String requestId) {
    JsonNode agreement;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(base + "/v2/agreements/" + command.path("agreementId").asText()))
              .timeout(timeout)
              .header("Authorization", "Bearer " + token)
              .header("X-Request-Id", requestId)
              .header("Accept", "application/json")
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404)
        throw ApiException.conflict("The referenced agreement does not exist.");
      if (response.statusCode() != 200 || response.body().length() > 1_048_576)
        throw ApiException.unavailable();
      agreement = JsonSupport.JSON.readTree(response.body());
      if (agreement == null || !contracts.valid("Agreement", agreement))
        throw ApiException.unavailable();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApiException.unavailable();
    } catch (IOException e) {
      throw ApiException.unavailable();
    }
    verify(agreement, command);
    return agreement;
  }

  static void verify(JsonNode agreement, JsonNode command) {
    if (!agreement.path("status").asText().equals("SIGNED"))
      throw ApiException.conflict("The agreement is not signed.");
    if (!agreement.path("id").equals(command.path("agreementId")))
      throw ApiException.conflict("Agreement identifier differs from the command.");
    for (String field : new String[] {"applicationId", "companyId", "dataVersion"})
      if (!JsonSupport.sameValue(agreement.path(field), command.path(field)))
        throw ApiException.conflict("Agreement does not match the command snapshot.");
    JsonNode offer = agreement.path("offer"),
        signature = agreement.path("signature"),
        document = agreement.path("document");
    if (!offer.path("applicationId").equals(agreement.path("applicationId"))
        || !JsonSupport.sameValue(offer.path("dataVersion"), agreement.path("dataVersion"))
        || !signature.path("customerId").equals(agreement.path("customerId"))
        || !JsonSupport.sameValue(signature.path("documentVersion"), document.path("version"))
        || !signature.path("documentSha256").equals(document.path("sha256"))
        || !JsonSupport.sha256(document.path("content").asText())
            .equals(document.path("sha256").asText())
        || !Instant.parse(signature.path("signedAt").asText())
            .isBefore(Instant.parse(agreement.path("signBy").asText())))
      throw ApiException.conflict(
          "Signed document, signature and offer snapshots are inconsistent.");
  }
}
