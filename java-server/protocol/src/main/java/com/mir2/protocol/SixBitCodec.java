package com.mir2.protocol;

import java.io.ByteArrayOutputStream;
/** Exact OLDMODE implementation of Common/EDcode.pas Encode6BitBuf/Decode6BitBuf. */
public final class SixBitCodec {
 private SixBitCodec(){}
 public static byte[] encode(byte[] source){
  ByteArrayOutputStream out=new ByteArrayOutputStream((source.length*4+2)/3); int rest=0, restBits=0;
  for(byte value:source){int ch=value&255; int made=(rest | (ch >>> (2+restBits)))&63; rest=((ch << (8-(2+restBits))) >>> 2)&63; restBits+=2;
   out.write(made+0x3c); if(restBits>=6){out.write(rest+0x3c);restBits=0;rest=0;}}
  if(restBits>0)out.write(rest+0x3c); return out.toByteArray();
 }
 public static byte[] decode(byte[] encoded){
  ByteArrayOutputStream out=new ByteArrayOutputStream(encoded.length*3/4); int bitPos=2,madeBits=0,tmp=0;
  for(byte raw:encoded){int ch=(raw&255)-0x3c;if(ch<0)break;if(madeBits+6>=8){out.write((tmp|((ch&63) >>> (6-bitPos)))&255);madeBits=0;if(bitPos<6)bitPos+=2;else{bitPos=2;continue;}}
   tmp=((ch<<bitPos)&((0xff << bitPos)&0xff))&255; madeBits+=8-bitPos;}
  return out.toByteArray();
 }
 public static String encodeString(byte[] bytes){return new String(encode(bytes),java.nio.charset.StandardCharsets.ISO_8859_1);}
 public static byte[] decodeString(String s){return decode(s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));}
}
