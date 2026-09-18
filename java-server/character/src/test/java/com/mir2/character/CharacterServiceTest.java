package com.mir2.character;
import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; import java.util.NoSuchElementException;
class CharacterServiceTest {
 @Test void createListDelete(){var s=new CharacterService();var c=s.create("a","传奇",1);assertEquals(1,s.list("a").size());s.delete("a",c.id());assertTrue(s.list("a").isEmpty());assertThrows(NoSuchElementException.class,()->s.delete("a",c.id()));}
 @Test void namesAreLimitedByGbkBytes(){var s=new CharacterService();assertThrows(IllegalArgumentException.class,()->s.create("a","传奇英雄",1));}
}
