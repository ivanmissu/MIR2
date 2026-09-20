package com.mir2.protocol;
import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; import java.nio.charset.StandardCharsets; import java.util.Arrays;
class ProtocolTest {
 @Test void packedMessageIsLittleEndian(){assertArrayEquals(new byte[]{4,3,2,1,0x34,0x12,(byte)0xff,(byte)0xff,0x00,(byte)0x80,0x78,0x56},new DefaultMessage(0x01020304,0x1234,0xffff,0x8000,0x5678).toBytes());}
 @Test void messageRoundTripsTwentyGoldenVectors(){for(int i=0;i<20;i++){var m=new DefaultMessage(i*7919-1000, i, i*17&65535, 65535-i, i*31&65535);assertEquals(m,MessageCodec.decode(MessageCodec.encode(m)));}}
 @Test void byteLimitIsGbkAware(){assertEquals("传奇",ByteStrings.fixedGbkText("传奇英雄",4));}
 @Test void desUsesZeroPaddingAndRoundTrips() { byte[] plain="账号密码".getBytes(StandardCharsets.UTF_8); byte[] encrypted=DesCodec.encrypt(plain,"key".getBytes(StandardCharsets.US_ASCII)); assertEquals(0, encrypted.length % 8); assertArrayEquals(plain,DesCodec.decrypt(encrypted,"key".getBytes(StandardCharsets.US_ASCII))); }
 @Test void sixBitRoundTripsBinary(){for(int n=0;n<80;n++){byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=(byte)(i*37);assertArrayEquals(b,SixBitCodec.decode(SixBitCodec.encode(b)));}}
}
