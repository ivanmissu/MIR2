package com.mir2.protocol;

/** Packed Delphi TDefaultMessage: Integer + four Word fields, little endian. */
public record DefaultMessage(int recog, int ident, int param, int tag, int series) {
  public DefaultMessage {
    if ((ident|param|tag|series) < 0 || ident > 0xffff || param > 0xffff || tag > 0xffff || series > 0xffff)
      throw new IllegalArgumentException("Word field outside unsigned 16-bit range");
  }
  public byte[] toBytes() {
    byte[] b = new byte[12]; int p=0;
    p=putIntLE(b,p,recog); p=putShortLE(b,p,ident); p=putShortLE(b,p,param); p=putShortLE(b,p,tag); putShortLE(b,p,series); return b;
  }
  public static DefaultMessage fromBytes(byte[] b) {
    if (b.length != 12) throw new IllegalArgumentException("TDefaultMessage must be exactly 12 bytes");
    return new DefaultMessage(intLE(b,0), shortLE(b,4), shortLE(b,6), shortLE(b,8), shortLE(b,10));
  }
  private static int putIntLE(byte[] b,int p,int v){b[p++]=(byte)v;b[p++]=(byte)(v>>>8);b[p++]=(byte)(v>>>16);b[p++]=(byte)(v>>>24);return p;}
  private static int putShortLE(byte[] b,int p,int v){b[p++]=(byte)v;b[p++]=(byte)(v>>>8);return p;}
  private static int intLE(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|(b[p+3]<<24);}
  private static int shortLE(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8);}
}
