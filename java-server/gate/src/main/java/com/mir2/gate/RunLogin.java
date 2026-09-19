package com.mir2.gate;

import java.util.Objects;

/** Decoded headerless first packet sent by mir2.exe after connecting to the GAME gate. */
public record RunLogin(
    String account,
    String characterName,
    int certification,
    int clientVersion,
    int loginCode) {
  public RunLogin {
    account = requireField(account, "account");
    characterName = requireField(characterName, "characterName");
    if (certification <= 0) throw new IllegalArgumentException("certification must be positive");
    if (clientVersion <= 0) throw new IllegalArgumentException("clientVersion must be positive");
    if (loginCode < 0) throw new IllegalArgumentException("loginCode must not be negative");
  }

  private static String requireField(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.indexOf('/') >= 0) throw new IllegalArgumentException("invalid " + name);
    return value;
  }
}
