package com.mir2.protocol;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Compatibility wrapper for Common/DES.pas EncryStr/DecryStr.
 * The Delphi implementation is DES ECB with zero padding (not PKCS#5), and
 * pads keys shorter than eight bytes with NUL. Ciphertext is returned raw.
 */
public final class DesCodec {
  private DesCodec() {}

  public static byte[] encrypt(byte[] plain, byte[] key) {
    return crypt(plain, key, Cipher.ENCRYPT_MODE, true);
  }

  public static byte[] decrypt(byte[] cipherText, byte[] key) {
    if (cipherText.length % 8 != 0) throw new IllegalArgumentException("DES input must be a multiple of 8 bytes");
    return trimZeroes(crypt(cipherText, key, Cipher.DECRYPT_MODE, false));
  }

  private static byte[] crypt(byte[] input, byte[] key, int mode, boolean pad) {
    try {
      byte[] k = Arrays.copyOf(key, 8);
      byte[] data = input;
      if (pad) data = Arrays.copyOf(input, (input.length + 7) / 8 * 8);
      Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
      cipher.init(mode, new SecretKeySpec(k, "DES"));
      return cipher.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("DES compatibility operation failed", e);
    }
  }

  private static byte[] trimZeroes(byte[] data) {
    int n = data.length;
    while (n > 0 && data[n - 1] == 0) n--;
    return Arrays.copyOf(data, n);
  }
}
