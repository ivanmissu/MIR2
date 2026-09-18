package com.mir2.character;

import com.mir2.protocol.ByteStrings;
import java.util.*; import java.util.concurrent.ConcurrentHashMap;

/** Character domain validation with pluggable durable storage. */
public final class CharacterService {
 private final CharacterStore store;
 public CharacterService(){this(new MemoryCharacterStore());}
 public CharacterService(CharacterStore store){this.store=Objects.requireNonNull(store);}
 public List<Character> list(String account){return store.list(account);}
 public Character create(String account,String name,int job){String safe=ByteStrings.fixedGbkText(Objects.requireNonNull(name),14);if(safe.isBlank()||!safe.equals(name))throw new IllegalArgumentException("name exceeds 14 GBK bytes");if(job<0||job>2)throw new IllegalArgumentException("job must be 0..2");if(store.list(account).stream().anyMatch(c->c.name().equals(name)))throw new IllegalArgumentException("name already exists");return store.save(new Character(UUID.randomUUID(),account,name,job,1));}
 public void delete(String account,UUID id){store.delete(account,id);}
 private static final class MemoryCharacterStore implements CharacterStore {private final Map<String,LinkedHashMap<UUID,Character>> data=new ConcurrentHashMap<>();public List<Character> list(String a){return List.copyOf(data.getOrDefault(a,new LinkedHashMap<>()).values());}public Character save(Character c){var m=data.computeIfAbsent(c.account(),x->new LinkedHashMap<>());synchronized(m){if(m.values().stream().anyMatch(x->x.name().equals(c.name())))throw new IllegalArgumentException("name already exists");m.put(c.id(),c);return c;}}public void delete(String a,UUID id){var m=data.get(a);if(m==null||m.remove(id)==null)throw new NoSuchElementException("character not found");}}
}
