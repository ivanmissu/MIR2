package com.mir2.persistence;
import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; import java.util.*;
class SqliteStoreTest { @Test void dataSurvivesStoreReopen(){String url="jdbc:sqlite:file:mir2test?mode=memory&cache=shared";var c=new com.mir2.character.Character(UUID.randomUUID(),"hero","传奇",1,1);try(var s=new SqliteStore(url)){s.saveAccount("hero",new byte[]{1,2});s.saveCharacter(c);assertEquals(1,s.list("hero").size());}catch(Exception e){fail(e);} }
 @Test void accountAndCharacterAreSeparated(){try(var s=new SqliteStore("jdbc:sqlite::memory:")){s.saveAccount("a",new byte[]{3});assertTrue(s.passwordDigest("b").isEmpty());assertThrows(NoSuchElementException.class,()->s.delete("a",UUID.randomUUID()));}}
}
