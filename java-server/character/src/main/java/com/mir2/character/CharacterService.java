package com.mir2.character;

import com.mir2.protocol.ByteStrings;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Character list/create/delete service; persistence adapter is the next SQLite task. */
public final class CharacterService {
  private final Map<String, LinkedHashMap<UUID, Character>> byAccount = new ConcurrentHashMap<>();
  public List<Character> list(String account) { return List.copyOf(byAccount.getOrDefault(account, new LinkedHashMap<>()).values()); }
  public Character create(String account, String name, int job) {
    String safeName=ByteStrings.fixedGbkText(Objects.requireNonNull(name), 14);
    if (safeName.isBlank() || !safeName.equals(name)) throw new IllegalArgumentException("name exceeds 14 GBK bytes");
    if (job < 0 || job > 2) throw new IllegalArgumentException("job must be 0..2");
    var chars=byAccount.computeIfAbsent(account, ignored->new LinkedHashMap<>());
    synchronized(chars){ if(chars.values().stream().anyMatch(c->c.name().equals(name))) throw new IllegalArgumentException("name already exists"); Character c=new Character(UUID.randomUUID(),account,name,job,1);chars.put(c.id(),c);return c; }
  }
  public void delete(String account, UUID id) { var chars=byAccount.get(account); if(chars==null || chars.remove(id)==null) throw new NoSuchElementException("character not found"); }
}
