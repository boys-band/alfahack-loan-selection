package ru.alfa.disbursement;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ValidationTest {
  private final ObjectMapper json = new ObjectMapper();
  private final Contracts contracts = new Contracts();

  private ObjectNode request() {
    ObjectNode n = json.createObjectNode();
    for (String key :
        new String[] {"applicationId", "agreementId", "companyId", "settlementAccountId"})
      n.put(key, "00000000-0000-4000-8000-000000000101");
    n.put("dataVersion", 1).put("payoutVersion", 1);
    return n;
  }

  @Test
  void acceptsOnlyStrictContractRequest() {
    assertDoesNotThrow(() -> contracts.request("PayoutCheckRequest", request()));
    for (Object value : new Object[] {null, "1", 1.5, 0}) {
      ObjectNode n = request();
      n.set("dataVersion", json.valueToTree(value));
      assertThrows(ApiException.class, () -> contracts.request("PayoutCheckRequest", n));
    }
    ObjectNode extra = request().put("amountRub", 500);
    assertThrows(ApiException.class, () -> contracts.request("PayoutCheckRequest", extra));
    ObjectNode missing = request();
    missing.remove("companyId");
    assertThrows(ApiException.class, () -> contracts.request("PayoutCheckRequest", missing));
  }

  @Test
  void accountOwnershipIsCheckedBeforeStatus() {
    assertEquals("ACCOUNT_NOT_OWNED", AccountRules.reason("other", "INVALID", "mine"));
    assertEquals("ACCOUNT_NOT_OWNED", AccountRules.reason(null, null, "mine"));
    assertEquals("ACCOUNT_INVALID", AccountRules.reason("mine", "INVALID", "mine"));
    assertNull(AccountRules.reason("mine", "ACTIVE", "mine"));
  }

  @Test
  void fingerprintIgnoresObjectOrderButPreservesTypesAndPath() throws Exception {
    assertEquals(
        JsonSupport.hash("/v2/payout-checks", json.readTree("{\"b\":2,\"a\":1}")),
        JsonSupport.hash("/v2/payout-checks", json.readTree("{\"a\":1,\"b\":2}")));
    assertNotEquals(
        JsonSupport.hash("/v2/payout-checks", request()),
        JsonSupport.hash("/v2/disbursements", request()));
    assertNotEquals(
        JsonSupport.hash("/v2/payout-checks", request()),
        JsonSupport.hash("/v2/payout-checks", request().putNull("companyId")));
  }

  @Test
  void rejectsVersionsOutsidePostgresqlBigintWithoutTruncating() {
    ObjectNode n = request();
    n.put("dataVersion", new java.math.BigInteger("9223372036854775808"));
    ApiException error =
        assertThrows(ApiException.class, () -> contracts.request("PayoutCheckRequest", n));
    assertEquals(422, error.status);
  }

  @Test
  void embeddedContractMatchesAcceptedRepositoryContract() throws Exception {
    assertArrayEquals(
        java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../../contracts/schemas.yaml")),
        java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("src/main/resources/contract-schemas.yaml")));
  }

  @Test
  void snapshotVersionsCompareAcrossJdbcAndJsonIntegerRepresentations() {
    assertTrue(
        JsonSupport.sameValue(
            json.getNodeFactory().numberNode(1L), json.getNodeFactory().numberNode(1)));
    assertFalse(
        JsonSupport.sameValue(
            json.getNodeFactory().numberNode(2L), json.getNodeFactory().numberNode(1)));
  }
}
