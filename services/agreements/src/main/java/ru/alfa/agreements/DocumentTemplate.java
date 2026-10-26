package ru.alfa.agreements;

import static ru.alfa.agreements.Json.*;
import static ru.alfa.agreements.Problem.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/** Versioned, deterministic UTF-8 document. Payout instructions are deliberately absent. */
final class DocumentTemplate {
  static final String VERSION = "demo-agreement-v1";

  private DocumentTemplate() {}

  static String sha256(String content) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static void check(JsonNode request, Instant now) {
    JsonNode offer = request.get("offer");
    require(
        offer.get("applicationId").equals(request.get("applicationId"))
            && Contract.same(offer.get("dataVersion"), request.get("dataVersion")),
        422,
        "SNAPSHOT_MISMATCH",
        "Предложение относится к другой версии заявки");
    Instant accepted = Instant.parse(text(request, "acceptedAt"));
    require(
        accepted.isBefore(Instant.parse(text(offer, "expiresAt"))),
        422,
        "SNAPSHOT_MISMATCH",
        "Предложение принято после окончания срока");
    require(
        !accepted.isAfter(now),
        422,
        "SNAPSHOT_MISMATCH",
        "Принятие предложения не может быть в будущем");
  }

  static ObjectNode render(JsonNode request) {
    JsonNode company = request.get("company"), offer = request.get("offer");
    // Quoted JSON strings preserve exact names/identifiers, including line breaks and punctuation.
    String content =
        """
            ДЕМОНСТРАЦИОННЫЙ КРЕДИТНЫЙ ДОГОВОР
            Шаблон: %s
            Заявка: %s; версия данных: %s
            Клиент: %s
            Заемщик: %s
            ИНН: %s; организационная форма: %s; дата регистрации: %s
            Компания: %s
            Принятое предложение: %s; версия: %s; тип: %s
            Продукт: %s; версия продукта: %s
            Оцененные условия: %s
            Сумма кредита: %s рублей
            Срок: %s месяцев
            Годовая ставка: %s базисных пунктов
            Ежемесячный платеж: %s рублей
            Предложение принято: %s
            Подпись моделируется согласием DEMO_CONSENT. Реальная электронная подпись и выдача денежных средств не выполняются.
            """
            .formatted(
                VERSION,
                text(request, "applicationId"),
                text(request, "dataVersion"),
                text(request, "customerId"),
                company.get("legalName").toString(),
                text(company, "inn"),
                text(company, "legalForm"),
                text(company, "registeredAt"),
                text(company, "id"),
                text(offer, "id"),
                text(offer, "version"),
                text(offer, "type"),
                text(offer, "productId"),
                text(offer, "productVersion"),
                text(offer, "termsId"),
                text(offer, "amountRub"),
                text(offer, "termMonths"),
                text(offer, "annualRateBps"),
                text(offer, "monthlyPaymentRub"),
                text(request, "acceptedAt"));
    return obj(
        "version",
        1,
        "mediaType",
        "text/plain; charset=utf-8",
        "content",
        content,
        "sha256",
        sha256(content));
  }

  static Instant deadline(Instant createdAt) {
    return createdAt.plus(72, ChronoUnit.HOURS);
  }
}
