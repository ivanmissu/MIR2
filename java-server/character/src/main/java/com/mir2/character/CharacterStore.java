package com.mir2.character;
import java.util.*;
/** Persistence port for character data. */
public interface CharacterStore { List<Character> list(String account); Character save(Character character); void delete(String account, UUID id); }
