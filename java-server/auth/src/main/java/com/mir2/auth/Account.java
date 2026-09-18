package com.mir2.auth;

import java.util.Objects;

public record Account(String username, byte[] passwordDigest) {
  public Account { Objects.requireNonNull(username); Objects.requireNonNull(passwordDigest); passwordDigest = passwordDigest.clone(); }
  @Override public byte[] passwordDigest() { return passwordDigest.clone(); }
}
