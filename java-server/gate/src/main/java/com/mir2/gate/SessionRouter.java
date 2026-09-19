package com.mir2.gate;

import com.mir2.auth.AuthService;
import com.mir2.character.Character;
import com.mir2.character.CharacterService;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/** Domain-level route boundary; transport handlers should call this instead of game services directly. */
public final class SessionRouter {
  private final AuthService auth;
  private final CharacterService characters;

  public SessionRouter(AuthService auth, CharacterService characters) {
    this.auth = Objects.requireNonNull(auth);
    this.characters = Objects.requireNonNull(characters);
  }

  public String login(String username, String password) {
    return auth.login(username, password);
  }

  public List<Character> characters(String session) {
    return characters.list(auth.accountFor(session));
  }

  public Character create(String session, String name, int job) {
    return create(session, name, job, 0, 0);
  }

  public Character create(String session, String name, int job, int hair, int gender) {
    return characters.create(auth.accountFor(session), name, job, hair, gender);
  }

  public void delete(String session, UUID id) {
    characters.delete(auth.accountFor(session), id);
  }

  public Character select(String session, UUID id) {
    return characters(session).stream()
        .filter(character -> character.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("character not found"));
  }
}
