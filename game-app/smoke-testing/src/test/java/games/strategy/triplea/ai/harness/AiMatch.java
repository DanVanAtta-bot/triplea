package games.strategy.triplea.ai.harness;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.data.properties.IEditableProperty;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.ProAiPatch;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.EndRoundDelegate;
import games.strategy.triplea.settings.ClientSetting;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Plays headless all-Hard-AI games where one alliance runs the {@code --patches} set and every
 * other player runs the {@code --baseline-patches} set (default none: stock Hard AI). Prints one
 * RESULT line per game.
 *
 * <p>Args: {@code --map <xml> --patched <alliance> --patches <P1,P2|none> [--baseline-patches
 * <P1,P2|none>] --rounds <cap> [--calc bounded|concurrent] [--props "Name=value,Name2=value"]
 * [--bid <file.bid.json>] [--swap] [--out <dir>] [--odds-runs <n>] [--flag-combat <tuv>]
 * [--flag-odds <0..1>]}. {@code --swap} plays a second game with the patched set given to the other
 * alliance (two-alliance maps only). {@code --bid} applies a fixed bid before round 1, see {@link
 * FixedBid}. {@code --props} overrides game options by their exact XML name; Tech Development is
 * always forced off. Every game writes {@code <out>/<game id>/} with {@code game.json}, {@code
 * records.jsonl} and the saves of flagged rounds; see {@link GameRecorder}.
 *
 * <p>Human starts: {@code --start <export.jsonl>@<round>@<player>}, or {@code --start-list <file>
 * --start-key {game_dir}} (one spec per line, picked per runner game, see {@link HumanStart#pick}),
 * rebuilds a forum-save position and plays on from it instead of the map's start, without the
 * recorder; {@code --play-rounds <n>} stops n rounds later at the starting step. See {@link
 * #playFromHuman}.
 */
public final class AiMatch {

  private AiMatch() {}

  public static void main(final String[] args) throws Exception {
    final Map<String, String> opts = parseArgs(args);
    final String map = opts.getOrDefault("map", "big_world_1942.xml");
    final int roundCap = Integer.parseInt(opts.getOrDefault("rounds", "30"));
    final Set<ProAiPatch> patches = parsePatches(opts.getOrDefault("patches", "NO_OP"));
    final Set<ProAiPatch> baselinePatches =
        parsePatches(opts.getOrDefault("baseline-patches", "none"));
    final FixedBid bid = opts.containsKey("bid") ? FixedBid.load(Path.of(opts.get("bid"))) : null;
    final String patchedAlliance = opts.getOrDefault("patched", "Axis");
    final Map<String, String> props = parseProps(opts.getOrDefault("props", ""));
    props.put(Constants.TECH_DEVELOPMENT, "false");

    final String calc = opts.getOrDefault("calc", "concurrent");
    if (!List.of("bounded", "concurrent").contains(calc)) {
      throw new IllegalArgumentException("--calc must be bounded or concurrent: " + calc);
    }
    final Path outRoot = Path.of(opts.getOrDefault("out", "build/ai-measure")).toAbsolutePath();
    final GameRecorder.Options recorderOptions =
        new GameRecorder.Options(
            Integer.parseInt(opts.getOrDefault("odds-runs", "500")),
            Integer.parseInt(opts.getOrDefault("flag-combat", "50")),
            Double.parseDouble(opts.getOrDefault("flag-odds", "0.6")));
    final Map<String, Object> runConfig = new LinkedHashMap<>();
    runConfig.put("map", map);
    runConfig.put("round_cap", roundCap);
    runConfig.put("calc", calc);
    runConfig.put("harness_odds_calc", "concurrent");
    runConfig.put("patches", patches.stream().map(Enum::name).sorted().toList());
    runConfig.put("baseline_patches", baselinePatches.stream().map(Enum::name).sorted().toList());
    runConfig.put("tech_development", false);
    runConfig.put("props", props);
    runConfig.put("dice_seed", null);
    runConfig.put("ai_seed", null);
    runConfig.put("engine_commit", git("rev-parse", "HEAD"));
    runConfig.put("engine_dirty", !git("status", "--porcelain", "--untracked-files=no").isEmpty());
    runConfig.put("java", System.getProperty("java.version"));
    runConfig.put("cpus", Runtime.getRuntime().availableProcessors());
    GameTestUtils.setUp();
    // ProAi resolves its static calculator once per JVM, on first use, from this setting.
    ClientSetting.useBoundedContextBattleCalc.setValue(calc.equals("bounded"));
    System.out.println("CALC " + calc);
    System.out.println("BID " + (bid == null ? "none" : bid.summary()));
    if (opts.containsKey("start") || opts.containsKey("start-list")) {
      if (bid != null || opts.containsKey("swap")) {
        throw new IllegalArgumentException("--bid and --swap don't apply to a human start");
      }
      final String spec =
          opts.containsKey("start")
              ? opts.get("start")
              : HumanStart.pick(
                  Path.of(opts.get("start-list")), opts.getOrDefault("start-key", ""));
      playFromHuman(
          map,
          roundCap,
          patches,
          baselinePatches,
          patchedAlliance,
          outRoot,
          HumanStart.read(spec),
          Integer.parseInt(opts.getOrDefault("play-rounds", "0")),
          props);
      System.exit(0);
    }
    final List<String> orientations = new ArrayList<>(List.of(patchedAlliance));
    if (opts.containsKey("swap")) {
      orientations.add(otherAlliance(map, patchedAlliance));
    }
    for (final String alliance : orientations) {
      playOne(
          map,
          roundCap,
          patches,
          baselinePatches,
          bid,
          alliance,
          outRoot,
          recorderOptions,
          runConfig,
          props);
    }
    // Game threads (battle calculator workers etc.) are not all daemons.
    System.exit(0);
  }

  private static void playOne(
      final String map,
      final int roundCap,
      final Set<ProAiPatch> patches,
      final Set<ProAiPatch> baselinePatches,
      @Nullable final FixedBid bid,
      final String patchedAlliance,
      final Path outRoot,
      final GameRecorder.Options recorderOptions,
      final Map<String, Object> runConfig,
      final Map<String, String> props) {
    final List<ProAi> ais = new ArrayList<>();
    final DecisionLog decisionLog = new DecisionLog();
    final PlayerTypes.Type patched = proAiType("Hard AI (patched)", patches, ais, decisionLog);
    final PlayerTypes.Type baseline =
        proAiType("Hard AI (baseline)", baselinePatches, ais, decisionLog);
    final Map<String, Integer> bidTuvBySide = new TreeMap<>();
    final long start = System.nanoTime();
    final ServerGame game =
        GameTestUtils.setUpGameWithAis(
            map,
            gameData -> {
              requireAlliance(gameData, patchedAlliance);
              props.forEach((name, value) -> setProperty(gameData, name, value));
              System.out.println("PROPS " + props);
              if (bid != null) {
                bidTuvBySide.putAll(bid.apply(gameData, map));
                System.out.println("BID applied, placed TUV by side " + bidTuvBySide);
              }
            },
            player -> allianceOf(player).equals(patchedAlliance) ? patched : baseline);
    game.setStopGameOnDelegateExecutionStop(true);
    final GameData data = game.getData();
    final Map<String, String> variantBySide = new LinkedHashMap<>();
    final Map<String, List<String>> patchesBySide = new LinkedHashMap<>();
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      final String side = allianceOf(player);
      final boolean onPatchedSide = side.equals(patchedAlliance);
      // With no patches anywhere both sides are stock, so neither is labelled "patched".
      final boolean isPatched = onPatchedSide && !(patches.isEmpty() && baselinePatches.isEmpty());
      variantBySide.put(side, isPatched ? "patched" : "baseline");
      patchesBySide.put(
          side,
          (onPatchedSide ? patches : baselinePatches).stream().map(Enum::name).sorted().toList());
    }
    final Map<String, Object> config = new LinkedHashMap<>(runConfig);
    config.put("patched_alliance", patchedAlliance);
    config.put("patches_by_side", patchesBySide);
    config.put("bid", bid == null ? null : bid.describe(bidTuvBySide));
    config.put("started_at", Instant.now().toString());
    final String gameId =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
            + "-"
            + UUID.randomUUID().toString().substring(0, 6);
    final GameRecorder recorder =
        new GameRecorder(
            game, outRoot, gameId, recorderOptions, decisionLog, variantBySide, config);
    System.out.println("RECORDS " + recorder.dir());
    while (!game.isGameOver() && !recorder.isOver() && data.getSequence().getRound() <= roundCap) {
      recorder.beforeStep();
      game.runNextStep();
      recorder.afterStep();
    }
    final double seconds = (System.nanoTime() - start) / 1e9;
    final boolean over = recorder.isOver();
    final Collection<GamePlayer> winners =
        over ? ((EndRoundDelegate) data.getEndRoundDelegate()).getWinners() : List.of();
    final int rounds = recorder.lastRound();
    final String winnerAlliance =
        winners.stream().map(AiMatch::allianceOf).distinct().collect(Collectors.joining("+"));
    final Map<String, Object> result = new LinkedHashMap<>();
    result.put("winner", winners.isEmpty() ? null : winnerAlliance);
    result.put("winners", winners.stream().map(GamePlayer::getName).sorted().toList());
    result.put("rounds", rounds);
    result.put("capped", !over);
    result.put("seconds", Math.round(seconds * 10) / 10.0);
    result.put("victory_cities", victoryCities(data));
    result.put("patch_hits_by_side", patchHitsBySide(data, ais, variantBySide.keySet()));
    recorder.finish(result);
    System.out.printf(
        "RESULT map=%s patched=%s patches=%s baselinePatches=%s bid=%s winner=%s winners=%s"
            + " rounds=%d capped=%s seconds=%.1f victoryCities=%s patchHitsBySide=%s%n",
        map,
        patchedAlliance,
        patches,
        baselinePatches,
        bid == null ? "none" : bid.shortHash(),
        winners.isEmpty() ? "none" : winnerAlliance,
        winners.stream().map(GamePlayer::getName).sorted().toList(),
        rounds,
        !over,
        seconds,
        victoryCities(data),
        patchHitsBySide(data, ais, variantBySide.keySet()));
    if (!game.isGameOver()) {
      // Resets ProAi's static battle calculator so a following game in this JVM starts clean.
      game.stopGame();
    }
  }

  /**
   * Plays on from a human position (see {@link HumanStart}) with the human game's options, then
   * {@code --props}. Writes {@code <out>/<game id>/play.jsonl} (see {@link PlayLog}). With {@code
   * playRounds > 0} it stops when the starting step comes round again that many rounds later and
   * also writes the history of the play; otherwise it plays to game over or the round cap.
   */
  private static void playFromHuman(
      final String map,
      final int roundCap,
      final Set<ProAiPatch> patches,
      final Set<ProAiPatch> baselinePatches,
      final String patchedAlliance,
      final Path outRoot,
      final HumanStart start,
      final int playRounds,
      final Map<String, String> props)
      throws IOException {
    final List<ProAi> ais = new ArrayList<>();
    final DecisionLog decisionLog = new DecisionLog();
    final PlayerTypes.Type patched = proAiType("Hard AI (patched)", patches, ais, decisionLog);
    final PlayerTypes.Type baseline =
        proAiType("Hard AI (baseline)", baselinePatches, ais, decisionLog);
    final Map<String, String> options = new TreeMap<>();
    final List<String> optionErrors = new ArrayList<>();
    final long t0 = System.nanoTime();
    final ServerGame game =
        GameTestUtils.setUpGameWithAis(
            map,
            gameData -> {
              requireAlliance(gameData, patchedAlliance);
              options.putAll(start.options(gameData));
              options.putAll(props);
              options.forEach(
                  (name, value) -> {
                    try {
                      setProperty(gameData, name, value);
                    } catch (final RuntimeException e) {
                      optionErrors.add(name + "=" + value + ": " + e.getMessage());
                    }
                  });
              System.out.println("PROPS " + options + " errors=" + optionErrors);
            },
            player -> allianceOf(player).equals(patchedAlliance) ? patched : baseline);
    game.setStopGameOnDelegateExecutionStop(true);
    final GameData data = game.getData();
    final List<String> missing = start.apply(game);
    final int startRound = data.getSequence().getRound();
    final String startStep = data.getSequence().getStep().getName();
    System.out.println(
        "START "
            + start.spec()
            + " at "
            + startStep
            + " round "
            + startRound
            + " missing="
            + missing);
    final String gameId =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
            + "-"
            + UUID.randomUUID().toString().substring(0, 6);
    final Path dir = outRoot.resolve(gameId);
    System.out.println("RECORDS " + dir);
    final EndRoundDelegate endRound = (EndRoundDelegate) data.getEndRoundDelegate();
    try (PlayLog log = new PlayLog(data, dir.resolve("play.jsonl"))) {
      final JsonObject header = new JsonObject();
      header.addProperty("t", "start");
      header.addProperty("spec", start.spec());
      header.addProperty("map", map);
      header.addProperty("round", startRound);
      header.addProperty("step", startStep);
      header.addProperty("player", start.player);
      header.addProperty("play_rounds", playRounds);
      header.addProperty("missing", missing.toString());
      header.addProperty("options", options.toString());
      header.addProperty("option_errors", optionErrors.toString());
      log.line(header);
      boolean stopped = false;
      while (!game.isGameOver()
          && endRound.getWinners().isEmpty()
          && data.getSequence().getRound() <= roundCap) {
        if (playRounds > 0
            && data.getSequence().getRound() == startRound + playRounds
            && data.getSequence().getStep().getName().equals(startStep)) {
          stopped = true;
          break;
        }
        log.beforeStep();
        game.runNextStep();
      }
      final Collection<GamePlayer> winners = endRound.getWinners();
      final String winnerAlliance =
          winners.stream().map(AiMatch::allianceOf).distinct().collect(Collectors.joining("+"));
      final boolean over = !winners.isEmpty();
      final JsonObject end = log.summary("end");
      end.addProperty("winner", over ? winnerAlliance : null);
      end.addProperty("capped", !over);
      end.addProperty("stopped_after_rounds", stopped);
      end.addProperty("seconds", Math.round((System.nanoTime() - t0) / 1e8) / 10.0);
      log.line(end);
      if (playRounds > 0) {
        log.exportHistory(Integer.MAX_VALUE);
      }
      System.out.printf(
          "RESULT map=%s patched=%s patches=%s baselinePatches=%s bid=none winner=%s winners=%s"
              + " rounds=%d capped=%s seconds=%.1f victoryCities=%s patchHitsBySide=%s%n",
          map,
          patchedAlliance,
          patches,
          baselinePatches,
          over ? winnerAlliance : "none",
          winners.stream().map(GamePlayer::getName).sorted().toList(),
          data.getSequence().getRound(),
          !over,
          (System.nanoTime() - t0) / 1e9,
          victoryCities(data),
          patchHitsBySide(data, ais, List.of("Axis", "Allies")));
    }
  }

  private static PlayerTypes.Type proAiType(
      final String label,
      final Set<ProAiPatch> patches,
      final List<ProAi> created,
      final DecisionLog decisionLog) {
    return new PlayerTypes.Type(label, false) {
      @Override
      public Player newPlayerWithName(final String name) {
        final ProAi ai = new ProAi(name, getLabel(), patches);
        ai.setDecisionListener(decisionLog);
        created.add(ai);
        return ai;
      }
    };
  }

  /** Side to player to patch hits, for every player that has patches; every side is a key. */
  private static Map<String, Map<String, Map<ProAiPatch, Integer>>> patchHitsBySide(
      final GameData data, final List<ProAi> ais, final Collection<String> sides) {
    final Map<String, Map<String, Map<ProAiPatch, Integer>>> hits = new TreeMap<>();
    sides.forEach(side -> hits.put(side, new TreeMap<>()));
    for (final ProAi ai : ais) {
      if (!ai.getProData().getPatches().isEmpty()) {
        final GamePlayer player = data.getPlayerList().getPlayerId(ai.getName());
        hits.computeIfAbsent(allianceOf(player), k -> new TreeMap<>())
            .put(ai.getName(), new TreeMap<>(ai.getProData().getPatchHits()));
      }
    }
    return hits;
  }

  private static Map<String, Integer> victoryCities(final GameData data) {
    final Map<String, Integer> byAlliance = new TreeMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      final int vc = TerritoryAttachment.get(t).map(TerritoryAttachment::getVictoryCity).orElse(0);
      if (vc > 0 && !t.getOwner().isNull()) {
        byAlliance.merge(allianceOf(t.getOwner()), vc, Integer::sum);
      }
    }
    return byAlliance;
  }

  static String allianceOf(final GamePlayer player) {
    return GameRecorder.sideOf(player);
  }

  private static String git(final String... args) {
    final List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    try {
      final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      final String output = new String(process.getInputStream().readAllBytes()).trim();
      return process.waitFor() == 0 ? output : "unknown";
    } catch (final IOException e) {
      return "unknown";
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return "unknown";
    }
  }

  private static void requireAlliance(final GameData data, final String alliance) {
    if (!data.getAllianceTracker().getAlliances().contains(alliance)) {
      throw new IllegalArgumentException(
          "No alliance " + alliance + "; map has " + data.getAllianceTracker().getAlliances());
    }
  }

  private static String otherAlliance(final String map, final String alliance) {
    final Path xml = Path.of("src", "test", "resources", "map-xmls", map);
    final Set<String> alliances =
        GameParser.parse(xml, false)
            .orElseThrow(() -> new IllegalArgumentException("Cannot parse " + xml))
            .getAllianceTracker()
            .getAlliances();
    final List<String> others = alliances.stream().filter(a -> !a.equals(alliance)).toList();
    if (others.size() != 1) {
      throw new IllegalArgumentException(
          "--swap needs exactly two alliances, map has " + alliances);
    }
    return others.get(0);
  }

  /**
   * Sets a game option the way the game-options UI does. {@code GameProperties.set} only writes a
   * constant, which an editable property of the same name shadows on read.
   */
  @SuppressWarnings("unchecked")
  public static void setProperty(final GameData data, final String name, final String value) {
    final Object current = data.getProperties().get(name);
    if (current == null) {
      throw new IllegalArgumentException("No game option named '" + name + "'");
    }
    final Object parsed;
    if (current instanceof Boolean) {
      if (!List.of("true", "false").contains(value)) {
        throw new IllegalArgumentException(name + " needs true or false: " + value);
      }
      parsed = Boolean.valueOf(value);
    } else if (current instanceof Integer) {
      parsed = Integer.valueOf(value);
    } else {
      parsed = value;
    }
    final IEditableProperty<Object> editable =
        (IEditableProperty<Object>) data.getProperties().getEditablePropertiesByName().get(name);
    if (editable != null) {
      editable.validateAndSet(parsed);
    } else {
      data.getProperties().set(name, parsed);
    }
    if (!data.getProperties().get(name).equals(parsed)) {
      throw new IllegalStateException("Game option '" + name + "' did not take " + value);
    }
  }

  private static Map<String, String> parseProps(final String value) {
    final Map<String, String> props = new TreeMap<>();
    for (final String pair : value.split(",")) {
      if (pair.isBlank()) {
        continue;
      }
      final int eq = pair.indexOf('=');
      if (eq < 0) {
        throw new IllegalArgumentException("--props entry needs Name=value: " + pair);
      }
      props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
    }
    return props;
  }

  private static Set<ProAiPatch> parsePatches(final String value) {
    if (value.equalsIgnoreCase("none")) {
      return Set.of();
    }
    final Set<ProAiPatch> patches = EnumSet.noneOf(ProAiPatch.class);
    Arrays.stream(value.split(","))
        .map(String::trim)
        .map(ProAiPatch::valueOf)
        .forEach(patches::add);
    return patches;
  }

  private static Map<String, String> parseArgs(final String[] args) {
    final Map<String, String> opts = new TreeMap<>();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("Unexpected argument: " + args[i]);
      }
      final String key = args[i].substring(2);
      if (key.equals("swap")) {
        opts.put(key, "true");
      } else if (i + 1 < args.length) {
        opts.put(key, args[++i]);
      } else {
        throw new IllegalArgumentException("Missing value for --" + key);
      }
    }
    return opts;
  }
}
