package com.mir2.persistence;
import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; import java.nio.file.*; import java.util.*;
class SqliteStoreTest {
 @Test void dataSurvivesStoreReopen() throws Exception {Path file=Files.createTempFile("mir2-", ".db");String url="jdbc:sqlite:"+file;var c=new com.mir2.character.Character(UUID.randomUUID(),"hero","传奇",1,1);try(var s=new SqliteStore(url)){s.saveAccount("hero",new byte[]{1,2});s.save(c);}try(var reopened=new SqliteStore(url)){assertArrayEquals(new byte[]{1,2},reopened.passwordDigest("hero").orElseThrow());assertEquals(c,reopened.list("hero").getFirst());}finally{Files.deleteIfExists(file);}}
 @Test void accountAndCharacterAreSeparated(){try(var s=new SqliteStore("jdbc:sqlite::memory:")){s.saveAccount("a",new byte[]{3});assertTrue(s.passwordDigest("b").isEmpty());assertThrows(NoSuchElementException.class,()->s.delete("a",UUID.randomUUID()));}}
}
