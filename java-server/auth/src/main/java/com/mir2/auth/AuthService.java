package com.mir2.auth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Account/session service. Account data is durable when constructed with a persistent AccountStore. */
public final class AuthService {
  private final AccountStore accounts; private final Map<String,String> sessions=new ConcurrentHashMap<>(); private final SecureRandom random=new SecureRandom();
  public AuthService(){this(new MemoryAccountStore());}
  public AuthService(AccountStore accounts){this.accounts=Objects.requireNonNull(accounts);}
  public void register(String username,String password){validate(username,password);if(accounts.find(username).isPresent())throw new IllegalArgumentException("account already exists");accounts.save(new Account(username,digest(password)));}
  public String login(String username,String password){Account a=accounts.find(username).orElseThrow(()->new SecurityException("invalid credentials"));if(!MessageDigest.isEqual(a.passwordDigest(),digest(password)))throw new SecurityException("invalid credentials");byte[] token=new byte[24];random.nextBytes(token);String session=HexFormat.of().formatHex(token);sessions.put(session,username);return session;}
  public String accountFor(String session){String a=sessions.get(session);if(a==null)throw new SecurityException("invalid session");return a;}
  public void logout(String session){sessions.remove(session);}
  private static byte[] digest(String password){try{return MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
  private static void validate(String u,String p){if(u==null||u.isBlank()||u.length()>32||p==null||p.isEmpty())throw new IllegalArgumentException("invalid account");}
  private static final class MemoryAccountStore implements AccountStore {private final Map<String,Account> data=new ConcurrentHashMap<>();public void save(Account a){if(data.putIfAbsent(a.username(),a)!=null)throw new IllegalArgumentException("account already exists");}public Optional<Account> find(String u){return Optional.ofNullable(data.get(u));}}
}
