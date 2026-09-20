package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import java.util.Objects;

/** One MIR2 client packet after the '#' / '!' transport envelope has been removed. */
public record WirePacket(DefaultMessage message, String encodedBody) {
  public WirePacket {
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(encodedBody, "encodedBody");
  }

  public WirePacket(DefaultMessage message) {
    this(message, "");
  }
}
