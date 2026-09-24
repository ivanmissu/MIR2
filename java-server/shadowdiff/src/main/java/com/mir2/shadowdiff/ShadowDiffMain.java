package com.mir2.shadowdiff;

import com.mir2.auth.AuthService;
import com.mir2.bootstrap.Mir2Server;
import com.mir2.bootstrap.ServerConfig;
import com.mir2.character.CharacterService;
import com.mir2.gate.GatePorts;
import com.mir2.persistence.SqliteStore;
import com.mir2.world.Ability;
import com.mir2.world.BackpackItem;
import com.mir2.world.Equipment;
import com.mir2.world.PlayerState;
import com.mir2.world.StdItem;
import com.mir2.world.StdItemsDb;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shadow-comparison harness (P2 影子对拍, the last unstarted P2 row): drives two servers
 * with one deterministic operation stream over the real client wire protocol and diffs the
 * player-observable state after every op.
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li><b>embedded</b> — boots two independent in-process {@link Mir2Server} assemblies
 *       (separate SQLite files, separate ports, identical config) and compares them. This is
 *       the self-check skeleton: it proves the harness, the op stream and the differ, and it
 *       doubles as a determinism regression for the Java server itself.</li>
 *   <li><b>remote</b> — compares any two already-running servers given their login gates.
 *       Point one side at the Delphi stack (LoginGate 7000) and the other at the Java stack
 *       and the same script becomes the Delphi-vs-Java 对拍 the G3 gate requires. No Delphi
 *       process is spawned here; the harness only speaks the wire.</li>
 * </ul>
 *
 * <p>W30 adds the interaction/persistence regression entries (W27 plan §4):
 * <ul>
 *   <li>{@code --duo party} / {@code --duo death-pk} — embedded, two accounts, two
 *       simultaneous sessions per server ({@link DuoHarness}); the party script covers
 *       group protocol, a shared kill with drops and the 12-cell share boundary, the
 *       death script covers PvP murder, PK points and 死亡自动退队;</li>
 *   <li>{@code --persistence} — embedded, a character pre-seeded with 木剑 / 布衣(男) /
 *       金创药(小量) plus 3000 gold runs the item lifecycle and must restore byte-identical
 *       state through a relog;</li>
 *   <li>{@code --lock} — embedded, the same seeding plus a DisableTakeOffList naming 木剑;
 *       taking the sword off must be refused observably.</li>
 * </ul>
 */
public final class ShadowDiffMain {

  /**
   * The P2/G3 first-ten monster slice, in the same order as the development plan.
   * Keep this deliberately separate from {@code MonsterTemplate}'s aliases: the matrix must
   * never silently grow into the still-forbidden remaining 47 monster behaviours.
   */
  static final List<String> AI_MONSTER_KINDS = List.of(
      "chicken", "deer", "scarecrow", "hookcat", "rakecat",
      "cavemaggot", "scorpion", "orc", "orcwarrior", "orcfighter");

  /**
   * The bag the {@code --persistence} / {@code --lock} scenarios seed the character with:
   * a wearable weapon, a wearable dress and a consumable, so equip/dress/eat/drop all have
   * real items to work on. All three resolve against the W18 authoritative StdItems
   * catalogue, so a typo here fails the boot rather than silently seeding a placeholder.
   */
  static final List<String> PERSISTENCE_SEED_ITEMS =
      List.of("木剑", "布衣(男)", "金创药(小量)");
  /** The wallet the seeded character starts with (also covers the gold column of §4). */
  static final long PERSISTENCE_SEED_GOLD = 3000;

  public static void main(String[] args) {
    int status;
    try {
      status = run(args);
    } catch (IllegalArgumentException usage) {
      System.err.println("[shadowdiff] " + usage.getMessage());
      System.err.println();
      System.err.println(USAGE);
      status = 2;
    } catch (Exception error) {
      System.err.println("[shadowdiff] failed: " + error);
      error.printStackTrace(System.err);
      status = 2;
    }
    System.exit(status);
  }

  static int run(String[] args) throws Exception {
    Map<String, String> options = Args.parse(args);
    if (options.containsKey("help")) {
      System.out.println(USAGE);
      return 0;
    }

    Duration settle = Duration.ofMillis(longOption(options, "settle-ms", 400));
    boolean strictMessages = options.containsKey("strict-messages");
    Path reportDir = Path.of(options.getOrDefault("report-dir", "reports"));
    String account = options.getOrDefault("account", "shadow01");
    String password = options.getOrDefault("password", "shadow-pw");
    String account2 = options.getOrDefault("account2", "shadow02");
    String password2 = options.getOrDefault("password2", "shadow-pw");
    String serverName = options.getOrDefault("server-name", "MIR2");

    boolean embedded = options.containsKey("embedded");
    if (options.containsKey("duo")) {
      if (!embedded)
        throw new IllegalArgumentException("--duo is only available with --embedded");
      return runDuo(options, settle, strictMessages, reportDir,
          account, password, account2, password2, serverName);
    }
    if (options.containsKey("persistence") || options.containsKey("lock")) {
      if (!embedded)
        throw new IllegalArgumentException("--persistence/--lock are only available with --embedded");
      if (options.containsKey("ai") || options.containsKey("ai-all") || options.containsKey("pve"))
        throw new IllegalArgumentException(
            "--persistence/--lock cannot be combined with --pve/--ai/--ai-all");
      return runSeededSolo(options, settle, strictMessages, reportDir,
          account, password, serverName, options.containsKey("lock"));
    }

    List<Op> script = loadScript(options, account, account2);
    if (embedded) {
      if (options.containsKey("ai-all")) {
        if (options.containsKey("monster-kind"))
          throw new IllegalArgumentException("--ai-all cannot be combined with --monster-kind");
        return runAiMatrix(options, script, settle, strictMessages, reportDir,
            account, password, serverName);
      }
      return runEmbedded(options, script, settle, strictMessages, reportDir,
          account, password, serverName);
    }
    if (options.containsKey("ai-all"))
      throw new IllegalArgumentException("--ai-all is only available with --embedded");
    return runRemote(options, script, settle, strictMessages, reportDir,
        account, password, serverName);
  }

  // ------------------------------------------------------------ modes

  private static int runEmbedded(Map<String, String> options, List<Op> script,
      Duration settle, boolean strictMessages, Path reportDir,
      String account, String password, String serverName) throws Exception {
    Path workDir = Files.createTempDirectory("mir2-shadowdiff-");
    long seed = longOption(options, "seed", 20260921);
    // Negative control: deliberately mis-seed the right-hand world so the harness must
    // report differences. A comparison that still passes with divergent seeds is not
    // observing anything, which is the failure mode this option exists to rule out.
    long rightSeed = longOption(options, "right-seed", seed);
    // Stationary trainer dummies by default: they neither chase nor retaliate, so the only
    // thing a `hit` op depends on is the seeded damage stream — the whole point of the PvE
    // comparison. `--monster-kind` can point at a walking monster, but then only the
    // clock-independent parts of the state stay comparable.
    boolean ai = options.containsKey("ai") || options.containsKey("ai-all");
    int monsters = (int) longOption(options, "monsters",
        ai ? 4 : options.containsKey("pve") ? 8 : 0);
    // --ai compares *moving* monsters, so it defaults to a walking template and a manual
    // world clock: world time then only advances on `tick` ops, making every AI decision a
    // scripted quantity rather than a race between two hosts.
    String monsterKind = options.getOrDefault("monster-kind", ai ? "chicken" : "trainer");
    com.mir2.world.WorldClock.Mode clockMode = ai
        ? com.mir2.world.WorldClock.Mode.MANUAL
        : com.mir2.world.WorldClock.Mode.SYSTEM;
    try (EmbeddedWorld left = EmbeddedWorld.boot(workDir.resolve("left"), "embedded-left",
            serverName, seed, monsters, monsterKind, clockMode,
            List.of(new SeededAccount(account, password, List.of(), 0)), null);
        EmbeddedWorld right = EmbeddedWorld.boot(workDir.resolve("right"), "embedded-right",
            serverName, rightSeed, monsters, monsterKind, clockMode,
            List.of(new SeededAccount(account, password, List.of(), 0)), null)) {
      return compare(left.target(), right.target(), script, settle, strictMessages,
          reportDir, account, password, serverName);
    }
  }

  /**
   * Runs the deterministic moving-AI comparison once for every template in the P2 first-ten
   * slice. Each row gets an isolated pair of servers/databases and its own detailed report;
   * failures do not short-circuit the remaining rows, so one invocation always yields a
   * complete G3 evidence table.
   */
  private static int runAiMatrix(Map<String, String> options, List<Op> script,
      Duration settle, boolean strictMessages, Path reportDir,
      String account, String password, String serverName) throws Exception {
    Files.createDirectories(reportDir);
    List<AiMatrixRow> rows = new ArrayList<>(AI_MONSTER_KINDS.size());
    for (String kind : AI_MONSTER_KINDS) {
      Map<String, String> rowOptions = new LinkedHashMap<>(options);
      rowOptions.put("ai", "true");
      rowOptions.put("monster-kind", kind);
      Path rowReportDir = reportDir.resolve(kind);
      System.out.printf(Locale.ROOT, "%n[shadowdiff] AI matrix: %s (%d/%d)%n",
          kind, rows.size() + 1, AI_MONSTER_KINDS.size());
      int status;
      String note = "";
      try {
        status = runEmbedded(rowOptions, script, settle, strictMessages, rowReportDir,
            account, password, serverName);
      } catch (Exception error) {
        status = 2;
        note = error.getClass().getSimpleName() + ": "
            + String.valueOf(error.getMessage()).replace('|', '/').replace('\n', ' ');
        System.err.printf(Locale.ROOT, "[shadowdiff] AI matrix row %s failed: %s%n", kind, error);
      }
      rows.add(new AiMatrixRow(kind, status, note));
    }

    Path summary = reportDir.resolve("ai-matrix.md");
    StringBuilder markdown = new StringBuilder()
        .append("# MIR2 first-ten monster AI shadow matrix\n\n")
        .append("Deterministic embedded comparison with a seeded random source and MANUAL world clock.\n\n")
        .append("| Monster | Verdict | Detailed report | Note |\n")
        .append("| --- | --- | --- | --- |\n");
    for (AiMatrixRow row : rows) {
      markdown.append("| `").append(row.kind()).append("` | **")
          .append(row.status() == 0 ? "PASS" : row.status() == 1 ? "FAIL" : "ERROR")
          .append("** | [shadow-report.md](").append(row.kind())
          .append("/shadow-report.md) | ").append(row.note()).append(" |\n");
    }
    long passed = rows.stream().filter(row -> row.status() == 0).count();
    markdown.append("\nResult: **").append(passed).append('/')
        .append(rows.size()).append(" PASS**.\n");
    Files.writeString(summary, markdown, StandardCharsets.UTF_8);
    System.out.printf(Locale.ROOT, "%n[shadowdiff] AI matrix=%d/%d PASS, report: %s%n",
        passed, rows.size(), summary.toAbsolutePath());
    return passed == rows.size() ? 0 : 1;
  }

  private record AiMatrixRow(String kind, int status, String note) {}

  /**
   * The W30 duo regression (W27 plan §4 场景 1/2/4): two accounts, two simultaneous sessions
   * per server, one script. Both servers boot with a MANUAL clock — the shared kill and the
   * scripted murder both need ≥18 pumped ticks between blows — and the party variant seeds
   * two chickens, whose 1/1 鸡肉 drop is the only deterministic ground-item source.
   */
  private static int runDuo(Map<String, String> options, Duration settle,
      boolean strictMessages, Path reportDir, String account, String password,
      String account2, String password2, String serverName) throws Exception {
    String scenario = options.get("duo");
    List<Op> script;
    int monsters;
    if (scenario.equalsIgnoreCase("party")) {
      script = substitute(Op.duoPartyScript(), account, account2);
      monsters = (int) longOption(options, "monsters", 2);
    } else if (scenario.equalsIgnoreCase("death-pk")) {
      script = substitute(Op.duoDeathPkScript(), account, account2);
      monsters = (int) longOption(options, "monsters", 0);
    } else if (options.containsKey("script")) {
      script = substitute(loadScript(options, account, account2), account, account2);
      monsters = (int) longOption(options, "monsters", 0);
    } else {
      throw new IllegalArgumentException(
          "--duo expects party, death-pk, or a --script file (got: " + scenario + ")");
    }
    String monsterKind = options.getOrDefault("monster-kind", "chicken");

    Path workDir = Files.createTempDirectory("mir2-shadowdiff-duo-");
    long seed = longOption(options, "seed", 20260921);
    long rightSeed = longOption(options, "right-seed", seed);
    List<SeededAccount> accounts = List.of(
        new SeededAccount(account, password, List.of(), 0),
        new SeededAccount(account2, password2, List.of(), 0));
    try (EmbeddedWorld left = EmbeddedWorld.boot(workDir.resolve("left"), "duo-left",
            serverName, seed, monsters, monsterKind, com.mir2.world.WorldClock.Mode.MANUAL,
            accounts, null);
        EmbeddedWorld right = EmbeddedWorld.boot(workDir.resolve("right"), "duo-right",
            serverName, rightSeed, monsters, monsterKind, com.mir2.world.WorldClock.Mode.MANUAL,
            accounts, null)) {
      System.out.printf(Locale.ROOT,
          "[shadowdiff] duo=%s left=%s right=%s, accounts=%s+%s, %d ops, %dx%s%n",
          scenario, left.target().label(), right.target().label(), account, account2,
          script.size(), monsters, monsterKind);

      DuoHarness.DuoRun leftRun = DuoHarness.observe(left.target(), script, settle,
          account, password, account2, password2, serverName);
      DuoHarness.DuoRun rightRun = DuoHarness.observe(right.target(), script, settle,
          account, password, account2, password2, serverName);

      ShadowDiff.Result primary = ShadowDiff.compare(
          left.target().label() + "/" + account, right.target().label() + "/" + account,
          leftRun.primary(), rightRun.primary(), strictMessages);
      ShadowDiff.Result partner = ShadowDiff.compare(
          left.target().label() + "/" + account2, right.target().label() + "/" + account2,
          leftRun.partner(), rightRun.partner(), strictMessages);

      Path primaryReport = ShadowReport.writeAll(reportDir.resolve("p1"), primary, script);
      Path partnerReport = ShadowReport.writeAll(reportDir.resolve("p2"), partner, script);

      System.out.printf(Locale.ROOT,
          "[shadowdiff] duo %s: p1=%s (state=%d acks=%d messages=%d, %s), "
              + "p2=%s (state=%d acks=%d messages=%d, %s)%n",
          scenario,
          primary.passed() ? "PASS" : "FAIL",
          primary.count(ShadowDiff.Severity.STATE), primary.count(ShadowDiff.Severity.ACKS),
          primary.count(ShadowDiff.Severity.MESSAGES), primaryReport.toAbsolutePath(),
          partner.passed() ? "PASS" : "FAIL",
          partner.count(ShadowDiff.Severity.STATE), partner.count(ShadowDiff.Severity.ACKS),
          partner.count(ShadowDiff.Severity.MESSAGES), partnerReport.toAbsolutePath());
      return primary.passed() && partner.passed() ? 0 : 1;
    }
  }

  /**
   * The W30 seeded solo scenarios (§4 场景 3/5): the character exists before the server
   * boots, with the harness-written bag and wallet (and, for {@code --lock}, a
   * DisableTakeOffList naming the seeded sword). Nothing here depends on world time, so the
   * worlds keep the production SYSTEM clock and no monsters.
   */
  private static int runSeededSolo(Map<String, String> options, Duration settle,
      boolean strictMessages, Path reportDir, String account, String password,
      String serverName, boolean lock) throws Exception {
    List<Op> script = options.containsKey("script")
        ? loadScript(options, account, account)
        : lock ? Op.lockScript() : Op.persistenceScript();
    List<String> items = lock ? List.of(PERSISTENCE_SEED_ITEMS.get(0)) : PERSISTENCE_SEED_ITEMS;
    Path workDir = Files.createTempDirectory(
        lock ? "mir2-shadowdiff-lock-" : "mir2-shadowdiff-persist-");
    long seed = longOption(options, "seed", 20260921);
    long rightSeed = longOption(options, "right-seed", seed);
    List<SeededAccount> accounts =
        List.of(new SeededAccount(account, password, items, PERSISTENCE_SEED_GOLD));

    Path disableTakeOffFile = null;
    if (lock) {
      // A one-line DisableTakeOffList.txt naming the seeded sword; the bootstrap loads it
      // through the same parser a production deployment uses (M2Share.pas:4578) — and that
      // parser reads the classic file as GBK, so the harness writes GBK too.
      disableTakeOffFile = workDir.resolve("DisableTakeOffList.txt");
      Files.write(disableTakeOffFile,
          ("; shadowdiff --lock: the seeded sword may never come off\n" + items.get(0) + "\n")
              .getBytes(java.nio.charset.Charset.forName("GBK")));
    }

    try (EmbeddedWorld left = EmbeddedWorld.boot(workDir.resolve("left"), "seeded-left",
            serverName, seed, 0, "trainer", com.mir2.world.WorldClock.Mode.SYSTEM,
            accounts, disableTakeOffFile);
        EmbeddedWorld right = EmbeddedWorld.boot(workDir.resolve("right"), "seeded-right",
            serverName, rightSeed, 0, "trainer", com.mir2.world.WorldClock.Mode.SYSTEM,
            accounts, disableTakeOffFile)) {
      return compare(left.target(), right.target(), script, settle, strictMessages,
          reportDir, account, password, serverName);
    }
  }

  /** Replaces the {p1}/{p2} name placeholders so fixed duo scripts survive account renames. */
  private static List<Op> substitute(List<Op> script, String account1, String account2) {
    List<Op> substituted = new ArrayList<>(script.size());
    for (Op op : script) {
      String text = op.text();
      if (text != null && (text.contains("{p1}") || text.contains("{p2}"))) {
        text = text.replace("{p1}", account1).replace("{p2}", account2);
      }
      substituted.add(new Op(op.kind(), op.direction(), text, op.millis(), op.actor()));
    }
    return List.copyOf(substituted);
  }

  private static int runRemote(Map<String, String> options, List<Op> script,
      Duration settle, boolean strictMessages, Path reportDir,
      String account, String password, String serverName) throws Exception {
    String leftHost = require(options, "left-host");
    String rightHost = require(options, "right-host");
    WireTarget left = new WireTarget(
        options.getOrDefault("left-label", "left"),
        leftHost,
        (int) longOption(options, "left-login-port", GatePorts.DEFAULT_LOGIN),
        (int) longOption(options, "left-select-port", 0),
        (int) longOption(options, "left-game-port", 0));
    WireTarget right = new WireTarget(
        options.getOrDefault("right-label", "right"),
        rightHost,
        (int) longOption(options, "right-login-port", GatePorts.DEFAULT_LOGIN),
        (int) longOption(options, "right-select-port", 0),
        (int) longOption(options, "right-game-port", 0));
    return compare(left, right, script, settle, strictMessages, reportDir,
        account, password, serverName);
  }

  private static int compare(WireTarget left, WireTarget right, List<Op> script,
      Duration settle, boolean strictMessages, Path reportDir,
      String account, String password, String serverName) throws Exception {
    System.out.printf(Locale.ROOT, "[shadowdiff] left=%s (%s:%d) right=%s (%s:%d), %d ops%n",
        left.label(), left.host(), left.loginPort(),
        right.label(), right.host(), right.loginPort(), script.size());

    List<OpObservation> leftRun = observe(left, script, settle, account, password, serverName);
    List<OpObservation> rightRun = observe(right, script, settle, account, password, serverName);

    ShadowDiff.Result result = ShadowDiff.compare(left.label(), right.label(),
        leftRun, rightRun, strictMessages);
    Path markdown = ShadowReport.writeAll(reportDir, result, script);

    System.out.printf(Locale.ROOT,
        "[shadowdiff] verdict=%s state=%d acks=%d messages=%d, report: %s%n",
        result.passed() ? "PASS" : "FAIL",
        result.count(ShadowDiff.Severity.STATE),
        result.count(ShadowDiff.Severity.ACKS),
        result.count(ShadowDiff.Severity.MESSAGES),
        markdown.toAbsolutePath());
    return result.passed() ? 0 : 1;
  }

  /** Runs the whole script against one target: enter, ops, close. */
  static List<OpObservation> observe(WireTarget target, List<Op> script, Duration settle,
      String account, String password, String serverName) throws IOException {
    List<OpObservation> observations = new ArrayList<>(script.size() + 1);
    try (ShadowSession session = new ShadowSession(target, account, password,
        account, serverName, settle)) {
      observations.add(session.enter());
      for (Op op : script) {
        observations.add(session.perform(op));
      }
    }
    return observations;
  }

  // ------------------------------------------------------------ embedded worlds

  /**
   * One account the harness registers before the server boots. An account with a non-empty
   * bag or a wallet additionally gets a pre-created character — a male warrior, the wearer
   * the seed's 布衣(男) requires ({@code gMan = 0}) — whose persisted state carries those
   * items, so item-dependent scenarios start with real inventory without inventing any new
   * server-side command.
   */
  record SeededAccount(String account, String password, List<String> bagItems, long gold) {
    SeededAccount {
      bagItems = List.copyOf(bagItems);
      if (gold < 0 || gold > PlayerState.MAX_GOLD)
        throw new IllegalArgumentException("seed gold must be within 0.." + PlayerState.MAX_GOLD);
    }
  }

  /** One in-process server with its own SQLite file and ports, seeded with the accounts. */
  private record EmbeddedWorld(Mir2Server server, WireTarget target) implements AutoCloseable {

  private static EmbeddedWorld boot(Path directory, String label, String serverName, long seed,
        int monsters, String monsterKind, com.mir2.world.WorldClock.Mode clockMode,
        List<SeededAccount> accounts, Path disableTakeOffFile) throws Exception {
      Files.createDirectories(directory);
      Path database = directory.resolve("mir2.db");
      try (SqliteStore store = new SqliteStore("jdbc:sqlite:" + database.toAbsolutePath())) {
        for (SeededAccount seeded : accounts) {
          AuthService auth = new AuthService(store);
          if (store.find(seeded.account()).isEmpty()) auth.register(seeded.account(), seeded.password());
          if (seeded.bagItems().isEmpty() && seeded.gold() == 0) continue;
          seedCharacterState(store, seeded);
        }
      }
      GatePorts ports = freePorts();
      // Both worlds share the map, the spawn cell AND the world seed: with MIR2_WORLD_SEED
      // pinned, each subsystem draws from its own stream (WorldRandom), so the Nth damage
      // roll is the same on both servers regardless of how the clock-driven subsystems
      // interleave. That is what makes the PvE ops below comparable at all.
      // The comparison worlds deliberately seat their dummies right next to the spawn so the
      // PvE script can reach them, so the start-point safe zone is switched off here. A real
      // deployment keeps the shipped nSafeZoneSize=10.
      ServerConfig config = new ServerConfig(database, ports, "127.0.0.1", serverName,
          null, "0", 20, 20, 50, monsters, monsterKind, null, null, seed)
          .withSafeZoneSize(0)
          .withWorldClockMode(clockMode)
          .withDisableTakeOffFile(disableTakeOffFile);
      Mir2Server server = new Mir2Server(config);
      server.start();
      System.out.printf(Locale.ROOT,
          "[shadowdiff] %s up: login=%d select=%d game=%d db=%s seed=%d monsters=%dx%s clock=%s%n",
          label, ports.login(), ports.select(), ports.game(), database, seed,
          monsters, monsterKind, clockMode.name().toLowerCase(Locale.ROOT));
      return new EmbeddedWorld(server, WireTarget.of(label, "127.0.0.1", ports.login()));
    }

    /**
     * Creates the character and writes its initial {@link PlayerState} — the bag resolved
     * against the authoritative StdItems catalogue, the wallet as given. MakeIndexes start
     * at 0 and are stabilised by the engine on enter, exactly like a W03-era row.
     */
    private static void seedCharacterState(SqliteStore store, SeededAccount seeded) {
      CharacterService characters = new CharacterService(store);
      if (characters.list(seeded.account()).stream()
          .anyMatch(character -> character.name().equals(seeded.account()))) {
        return; // already seeded (retry after a partial boot)
      }
      // A male warrior (gMan = 0), so the seeded 布衣(男) passes CheckTakeOnItems' gender
      // lock; hair 2 matches what the wire's CM_NEWCHR literal produces. Job 0 = warrior.
      var character = characters.create(seeded.account(), seeded.account(), 0, 2, 0);
      List<BackpackItem> bag = new ArrayList<>();
      for (String itemName : seeded.bagItems()) {
        StdItem template = StdItemsDb.byName(itemName).orElseThrow(
            () -> new IllegalArgumentException("cannot seed unknown standard item: " + itemName));
        bag.add(BackpackItem.of(template, 0));
      }
      store.save(new PlayerState(character.id(), Ability.defaultPlayer(), bag,
          Equipment.empty(), seeded.gold(), 0, 0));
    }

    @Override
    public void close() {
      server.close();
    }
  }

  private static GatePorts freePorts() throws IOException {
    int login = availablePort();
    int select = availablePort();
    int game = availablePort();
    while (select == login) select = availablePort();
    while (game == login || game == select) game = availablePort();
    return new GatePorts(login, select, game);
  }

  private static int availablePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  // ------------------------------------------------------------ options

  private static List<Op> loadScript(Map<String, String> options,
      String account, String account2) throws IOException {
    String scriptFile = options.get("script");
    if (scriptFile != null) {
      return Op.parseScript(Files.readString(Path.of(scriptFile), StandardCharsets.UTF_8));
    }
    // --pve selects the built-in combat script; it only means anything with a pinned seed
    // and trainer dummies, which is exactly what --pve configures below.
    if (options.containsKey("ai") || options.containsKey("ai-all")) return Op.aiScript();
    return options.containsKey("pve") ? Op.pveScript() : Op.defaultScript();
  }

  private static String require(Map<String, String> options, String key) {
    String value = options.get(key);
    if (value == null) throw new IllegalArgumentException("--" + key + " is required");
    return value;
  }

  private static long longOption(Map<String, String> options, String key, long fallback) {
    String value = options.get(key);
    return value == null ? fallback : Long.parseLong(value);
  }

  /** Package-private so {@code ShadowDiffMainArgsTest} can pin the value-less flag set. */
  static final class Args {
    /**
     * Value-less switches. A flag missing from this set silently swallows the next
     * argument, which is how {@code --ai --monsters 4} once died on "unexpected argument:
     * 4" — every new switch has to be listed here.
     */
    static final java.util.Set<String> FLAGS =
        java.util.Set.of("embedded", "help", "strict-messages", "pve", "ai", "ai-all",
            "persistence", "lock");

    static Map<String, String> parse(String[] args) {
      Map<String, String> options = new LinkedHashMap<>();
      for (int index = 0; index < args.length; index++) {
        String argument = args[index];
        if (!argument.startsWith("--"))
          throw new IllegalArgumentException("unexpected argument: " + argument);
        String key = argument.substring(2);
        if (FLAGS.contains(key)) {
          options.put(key, "true");
          continue;
        }
        if (index + 1 >= args.length)
          throw new IllegalArgumentException("--" + key + " requires a value");
        options.put(key, args[++index]);
      }
      return options;
    }
  }

  private static final String USAGE = """
      MIR2 shadowdiff — 影子对拍 harness（同一操作流驱动双服并 diff 状态快照）

      用法:
        java -jar mir2-shadowdiff.jar --embedded [选项]
            进程内拉起两个独立 Java 服务端（独立 SQLite + 独立端口 + 相同配置），
            回放同一操作流并对拍 —— 骨架自检 + Java 服务端确定性回归。

        java -jar mir2-shadowdiff.jar --left-host H1 --right-host H2 [选项]
            对拍两个已运行的服务端（Delphi 或 Java 均可，只要说 mir2.exe 的线上协议）。
            Delphi 侧就绪后：--left-host 指向 Delphi LoginGate，--right-host 指向 Java。

      选项:
        --script FILE          操作脚本（每行一个 op，# 注释；缺省用内置冒烟脚本）。
                               op: turn/walk/run/hit/heavyhit/bighit <dir 0..7>,
                                   pickup, bag, say <text>, drop/eat/takeon/takeoff <物品名>,
                                   groupmode <0|1>, groupcreate/groupadd/groupdel <玩家名>,
                                   sleep <ms>, tick <N>, relog
                               行首可加 p1 / p2 前缀指定双人对拍中的执行会话（W30），
                               无前缀 = p1；{p1}/{p2} 占位符在 duo 模式下替换为账号名。
                               tick <N> = 把 MANUAL 世界时钟推进 N 个 tick（经 @tick 命令），
                               普通时钟下服务端拒绝执行，脚本仍可跑但不推进时间。
        --settle-ms N          每个 op 后的静默窗口毫秒（默认 400；两台服务器共用）
        --strict-messages      消息集合差异也判 FAIL（默认仅提示）
        --report-dir DIR       报告目录（默认 reports，生成 shadow-report.md / .csv；
                               duo 模式生成 p1/ 与 p2/ 两份）
        --account S            测试账号（默认 shadow01；角色同名）
        --password S           密码（默认 shadow-pw；remote 模式须两边都能登录）
        --account2 S           （duo）第二位玩家的账号（默认 shadow02；角色同名）
        --password2 S          （duo）第二位玩家的密码（默认 shadow-pw）
        --server-name NAME     服务器名（默认 MIR2，须与两边一致）
        --seed N               （embedded）世界随机种子（默认 20260921）。两侧共用同一个种子，
                               伤害/掉落等各自独立成流，PvE 对拍才可复现；生产默认不设种子
                               （等价 Delphi 的全局 Random）。remote 模式请用 MIR2_WORLD_SEED
                               给两台服务端配同一个值。
        --pve                  用内置 PvE 对拍脚本（走到木桩前连续攻击），并默认放 8 个木桩。
                               需要两侧同种子；这是「静止怪对拍」的开箱即用入口。
        --ai                   （embedded）**会动的怪对拍**（W23）：两侧世界改用 MANUAL 世界时钟
                               （时间只在 tick op 推进），默认放 4 只鸡并用内置 AI 脚本。
                               怪物走位/攻击节拍因此只由 tick 数决定，与两台主机的墙钟无关；
                               状态快照新增 near=（视野内其它角色的格子+朝向）与 worldTime=。
                               remote 模式需要两台服务端都以 MANUAL 时钟启动（非生产配置）。
        --ai-all               （embedded）W24 首批 10 种怪 AI 矩阵：依次以 chicken/deer/
                               scarecrow/hookcat/rakecat/cavemaggot/scorpion/orc/orcwarrior/
                               orcfighter 运行 --ai；每种使用隔离双服并继续收集失败行，最终生成
                               ai-matrix.md 与十份详细报告。不得与 --monster-kind 同用。
        --duo party            （embedded，W30）**双人对拍·组队**：每侧双账号双会话同时在线，
                               内置脚本覆盖 组队协议全链路（含 -4 拒绝码）、双人共享击杀经验
                               与 1/1 鸡肉掉落拾取、12 格分摊边界（p2 走出范围后独享）、
                               队长踢人与双人重登一致性；世界为 MANUAL 时钟 + 2 只鸡。
        --duo death-pk         （embedded，W30）**双人对拍·死亡/PK**：p2 谋杀 p1 —— 交手染色、
                               SM_DEATH、死亡自动退队（handleDeath→leaveGroup→解散）、+100 PK
                               点与原版 GBK 文案、受害者重登复活（14 HP）、凶手 PK 点跨重登落库。
                               无怪、MANUAL 时钟。
        --duo <file>           与 --script FILE 连用：自定义双人脚本。
        --persistence          （embedded，W30）**持久化回归**：预种角色（木剑/布衣(男)/金创药(小量)
                               + 3000 金币）跑 穿戴/丢弃/拾取/食用/脱下 全生命周期后重登，
                               金币/装备/背包必须逐字节一致。SYSTEM 时钟、无怪。
        --lock                 （embedded，W30）**装备锁定回归**：同上种子但 DisableTakeOffList
                               锁定木剑 —— 穿戴成功、取下被拒（SM_TAKEOFF_FAIL +
                               SM_SYSMESSAGE 无法取下物品）、锁定跨重登保持。
        --right-seed N         （embedded）只给右侧换种子 —— 负向对照：对拍必须因此 FAIL，
                               用来证明本次判定不是空转。
        --monsters N           （embedded）出生点周围放 N 只怪（默认 0；--pve 时默认 8，--ai/--ai-all
                               时默认 4，--duo party 时默认 2，--duo death-pk 时默认 0）
        --monster-kind NAME    （embedded）怪物模板（默认 trainer/木桩：站桩不还手，
                               行为与墙钟无关，是唯一可确定性对拍的 PvE 目标；duo 模式默认 chicken）
        --left-label/-host/-login-port/-select-port/-game-port    左侧目标
        --right-label/-host/-login-port/-select-port/-game-port   右侧目标

      判定:
        STATE   状态快照差异（地图/坐标/朝向/HP/MP/等级/经验/金币/背包/装备/战斗/视野内角色/
                世界时间/组队名单/自身名字颜色）→ FAIL
                战斗 = 本 op 期间观测到的 SM_STRUCK 伤害与 HP、SM_DEATH、SM_WINEXP
        ACKS    +GOOD/+FAIL 应答序列差异 → FAIL
        MESSAGES 服务端消息集合差异 → 提示（--strict-messages 时 FAIL）
        duo 模式 = 两个玩家流分别对拍，任一 FAIL 即 FAIL

      退出码: 0 = PASS, 1 = FAIL, 2 = 参数/环境错误
      """;

  private ShadowDiffMain() {}
}
