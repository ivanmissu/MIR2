package com.mir2.auth;
import java.util.Optional;
/** Persistence port for account data; implementations may be memory, SQLite, or legacy DB importer. */
public interface AccountStore { void save(Account account); Optional<Account> find(String username); }
