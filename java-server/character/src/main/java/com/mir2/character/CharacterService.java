package com.mir2.character;

import com.mir2.protocol.ByteStrings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Character domain validation with pluggable durable storage. */
public final class CharacterService {
  private final CharacterStore store;

  public CharacterService() {
    this(new MemoryCharacterStore());
  }

  public CharacterService(CharacterStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public List<Character> list(String account) {
    return store.list(account);
  }

  public Character create(String account, String name, int job) {
    return create(account, name, job, 0, 0);
  }

  /** Creates a character from CM_NEWCHR's job, hair and gender fields. */
  public Character create(String account, String name, int job, int hair, int gender) {
    String safe = ByteStrings.fixedGbkText(Objects.requireNonNull(name), 14);
    if (safe.isBlank() || !safe.equals(name)) {
      throw new IllegalArgumentException("name exceeds 14 GBK bytes");
    }
    if (job < 0 || job > 2) throw new IllegalArgumentException("job must be 0..2");
    if (hair < 0 || hair > 127) throw new IllegalArgumentException("hair must be 0..127");
    if (gender < 0 || gender > 1) throw new IllegalArgumentException("gender must be 0 or 1");
    if (store.list(account).stream().anyMatch(character -> character.name().equals(name))) {
      throw new IllegalArgumentException("name already exists");
    }
    return store.save(new Character(
        UUID.randomUUID(), account, name, job, 1, gender, hair, 0, 0));
  }

  public void delete(String account, UUID id) {
    store.delete(account, id);
  }

  private static final class MemoryCharacterStore implements CharacterStore {
    private final Map<String, LinkedHashMap<UUID, Character>> data = new ConcurrentHashMap<>();

    @Override
    public List<Character> list(String account) {
      return List.copyOf(data.getOrDefault(account, new LinkedHashMap<>()).values());
    }

    @Override
    public Character save(Character character) {
      LinkedHashMap<UUID, Character> characters =
          data.computeIfAbsent(character.account(), ignored -> new LinkedHashMap<>());
      synchronized (characters) {
        if (characters.values().stream()
            .anyMatch(existing -> existing.name().equals(character.name()))) {
          throw new IllegalArgumentException("name already exists");
        }
        characters.put(character.id(), character);
        return character;
      }
    }

    @Override
    public void delete(String account, UUID id) {
      LinkedHashMap<UUID, Character> characters = data.get(account);
      if (characters == null || characters.remove(id) == null) {
        throw new NoSuchElementException("character not found");
      }
    }
  }
}
