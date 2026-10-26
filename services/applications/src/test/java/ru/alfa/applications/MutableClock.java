package ru.alfa.applications;

import java.time.*;

final class MutableClock extends Clock {
  private volatile Instant value = Instant.parse("2030-01-01T00:00:00Z");

  void set(Instant value) {
    this.value = value;
  }

  void advance(long seconds) {
    value = value.plusSeconds(seconds);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return value;
  }
}
