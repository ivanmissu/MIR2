package com.mir2.protocol;

import java.nio.ByteBuffer; import java.nio.charset.*; import java.util.Arrays;
/** Delphi AnsiString-compatible boundary helpers. Limits are bytes, never Java chars. */
public final class ByteStrings {
 private static final Charset GBK=Charset.forName("GBK"); private ByteStrings(){}
 public static byte[] gbk(String s){return s.getBytes(GBK);}
 public static String fromGbk(byte[] b){return new String(b,GBK);}
 public static byte[] fixedGbk(String s,int max){
  if(max<0) throw new IllegalArgumentException("max must be non-negative"); byte[] src=gbk(s); int n=Math.min(src.length,max);
  CharsetDecoder decoder=GBK.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
  while(n>0){try{decoder.decode(ByteBuffer.wrap(src,0,n)); return Arrays.copyOf(src,n);}catch(CharacterCodingException e){n--; decoder.reset();}}
  return new byte[0];
 }
 public static String fixedGbkText(String s,int max){return fromGbk(fixedGbk(s,max));}
}
