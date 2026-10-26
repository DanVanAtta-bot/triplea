package games.strategy.triplea.ai.kata;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameSequence;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.engine.random.PlainRandomSource;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.harness.AiMatch;
import games.strategy.triplea.ai.pro.ProAiPatch;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.settings.ClientSetting;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import javax.annotation.Nullable;

/**
 * Kata runner: replays one player's turn from a saved position under different variants, plays the
 * game on with stock Hard AI, and scores the result. Subcommands:
 *
 * <ul>
 *   <li>{@code run --save F --player P --variant V [--variant V2 ...] [--n 5] [--seeds 1,2]
 *       [--rounds 1] [--step S] [--later none|same] [--calc concurrent|bounded] [--out F.json]}
 *   <li>{@code save --map X.xml --round R --step S --out F [--seed N]}: plays all-Hard-AI to the
 *       start of step S in round R and saves there.
 *   <li>{@code save --from F --edit E.json --out F2}: cuts a position down (owners, units).
 *   <li>{@code dump --save F [--player P] [--out F.json]}: units per territory as JSON.
 * </ul>
 *
 * <p>A variant is {@code ai:<PATCH,PATCH|none>} or {@code script:<file.json>} (see {@link
 * KataScript}).
 */
public final class Kata {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private Kata() {}

  public static void main(final String[] args) throws Exception {
    if (args.length == 0) {
      throw new IllegalArgumentException("usage: run|save|dump --opt value ...");
    }
    final Map<String, List<String>> opts = parseArgs(Arrays.copyOfRange(args, 1, args.length));
    GameTestUtils.setUp();
    final String calc = one(opts, "calc", "concurrent");
    if (!List.of("bounded", "concurrent").contains(calc)) {
      throw new IllegalArgumentException("--calc must be bounded or concurrent: " + calc);
    }
    // ProAi resolves its static calculator once per JVM, on first use, from this setting.
    ClientSetting.useBoundedContextBattleCalc.setValue(calc.equals("bounded"));
    switch (args[0]) {
      case "run" -> run(opts);
      case "save" -> save(opts);
      case "dump" -> dump(opts);
      default -> throw new IllegalArgumentException("Unknown subcommand: " + args[0]);
    }
    // Game threads (battle calculator workers etc.) are not all daemons.
    System.exit(0);
  }

  // ---------------------------------------------------------------- run

  private record Variant(String label, Set<ProAiPatch> patches, @Nullable KataScript script) {}

  private static void run(final Map<String, List<String>> opts) throws IOException {
    final Path save = Path.of(required(opts, "save"));
    final String playerName = required(opts, "player");
    final int n = Integer.parseInt(one(opts, "n", "5"));
    final int extraRounds = Integer.parseInt(one(opts, "rounds", "1"));
    final boolean laterSame = one(opts, "later", "none").equals("same");
    final List<Long> seeds =
        opts.containsKey("seeds")
            ? Arrays.stream(one(opts, "seeds", "").split(",")).map(Long::parseLong).toList()
            : LongStream.rangeClosed(1, n).boxed().toList();
    final List<Variant> variants = new ArrayList<>();
    for (final String spec : opts.getOrDefault("variant", List.of("ai:none"))) {
      variants.add(parseVariant(spec));
    }
    final Map<String, List<KataScore.Result>> results = new LinkedHashMap<>();
    variants.forEach(v -> results.put(v.label(), new ArrayList<>()));
    // Interleave variants per seed so each seed's dice are shared across variants.
    for (final long seed : seeds) {
      for (final Variant variant : variants) {
        final KataScore.Result r =
            playOne(
                save, playerName, one(opts, "step", null), variant, seed, extraRounds, laterSame);
        printResult(r, playerName);
        results.get(variant.label()).add(r);
      }
    }
    printSummary(results);
    if (opts.containsKey("out")) {
      final Path out = Path.of(one(opts, "out", null));
      Files.writeString(out, GSON.toJson(results));
      System.out.println("Wrote " + out.toAbsolutePath());
    }
  }

  private static KataScore.Result playOne(
      final Path save,
      final String playerName,
      final @Nullable String stepName,
      final Variant variant,
      final long seed,
      final int extraRounds,
      final boolean laterSame) {
    final long started = System.nanoTime();
    final GameData data = load(save);
    final GamePlayer tested = player(data, playerName);
    positionAtTestedTurn(data, tested, stepName);
    final int startRound = data.getSequence().getRound();
    final int endRound = startRound + extraRounds;

    final List<KataAi> kataAis = new ArrayList<>();
    final PlayerTypes.Type kataType =
        new PlayerTypes.Type("Hard AI (kata)", false) {
          @Override
          public Player newPlayerWithName(final String name) {
            final KataAi ai =
                new KataAi(
                    name,
                    variant.patches(),
                    laterSame ? variant.patches() : Set.of(),
                    variant.script());
            kataAis.add(ai);
            return ai;
          }
        };
    final ServerGame game =
        GameTestUtils.setUpGameWithAis(data, p -> p.equals(tested) ? kataType : PlayerTypes.PRO_AI);
    game.setStopGameOnDelegateExecutionStop(true);
    game.setRandomSource(new PlainRandomSource(seed));
    final KataAi ai = kataAis.get(0);
    ai.setTestedTurn(true);
    final KataScore score = new KataScore(data, tested);

    boolean testedTurn = true;
    final boolean resumesStep =
        data.getProperties().get(ServerGame.GAME_HAS_BEEN_SAVED_PROPERTY, false);
    if (resumesStep) {
      // Re-runs the step the save was made at.
      scoredStep(game, score, game::setUpGameForRunningSteps);
    } else {
      game.setUpGameForRunningSteps();
    }
    final GameSequence sequence = data.getSequence();
    while (!game.isGameOver() && sequence.getRound() <= endRound) {
      if (testedTurn && !tested.equals(sequence.getStep().getPlayerId())) {
        testedTurn = false;
        ai.setTestedTurn(false);
        score.markEndOfTestedTurn();
      }
      scoredStep(game, score, game::runNextStep);
    }
    final KataScore.Result r = score.result(playerName);
    r.variant = variant.label();
    r.seed = seed;
    r.seconds = (System.nanoTime() - started) / 1e9;
    r.gameOver = game.isGameOver();
    r.endedAt = "round " + sequence.getRound() + " " + sequence.getStep().getName();
    r.actions = ai.getActions();
    if (!game.isGameOver()) {
      // Resets ProAi's static battle calculator so the next repetition starts clean.
      game.stopGame();
    }
    return r;
  }

  private static void scoredStep(
      final ServerGame game, final KataScore score, final Runnable runStep) {
    final GameStep step = game.getData().getSequence().getStep();
    final KataScore.Snapshot before = score.snapshot();
    runStep.run();
    score.recordStep(before, score.snapshot(), step.getDelegate());
  }

  /**
   * Makes the tested player's turn current: keeps the save's step if it belongs to the player, else
   * jumps to {@code stepName} (or the player's purchase step) in the current round.
   */
  private static void positionAtTestedTurn(
      final GameData data, final GamePlayer tested, final @Nullable String stepName) {
    final GameSequence sequence = data.getSequence();
    if (stepName == null && tested.equals(sequence.getStep().getPlayerId())) {
      return;
    }
    final GameStep target =
        stepName != null
            ? stepNamed(data, stepName)
            : stepsOf(data, tested).stream()
                .filter(s -> GameStep.isPurchaseStepName(s.getName()))
                .findFirst()
                .orElseThrow(
                    () -> new IllegalArgumentException(tested.getName() + " has no purchase step"));
    jumpTo(data, sequence.getRound(), target);
  }

  /** {@link GameSequence#setRoundAndStep} silently falls back to step 0; this one throws. */
  private static void jumpTo(final GameData data, final int round, final GameStep target) {
    data.getSequence().setRoundAndStep(round, target.getDisplayName(), target.getPlayerId());
    final GameStep landed = data.getSequence().getStep();
    if (!landed.getName().equals(target.getName())) {
      throw new IllegalStateException(
          "setRoundAndStep landed on " + landed.getName() + " instead of " + target.getName());
    }
  }

  private static GameStep stepNamed(final GameData data, final String name) {
    for (final GameStep step : data.getSequence()) {
      if (step.getName().equals(name)) {
        return step;
      }
    }
    final List<String> names = new ArrayList<>();
    data.getSequence().forEach(s -> names.add(s.getName()));
    throw new IllegalArgumentException("No step " + name + "; steps: " + names);
  }

  private static List<GameStep> stepsOf(final GameData data, final GamePlayer player) {
    final List<GameStep> steps = new ArrayList<>();
    data.getSequence()
        .forEach(
            s -> {
              if (player.equals(s.getPlayerId())) {
                steps.add(s);
              }
            });
    return steps;
  }

  private static Variant parseVariant(final String spec) throws IOException {
    if (spec.startsWith("ai:")) {
      return new Variant(spec, parsePatches(spec.substring(3)), null);
    }
    if (spec.startsWith("script:")) {
      final Path file = Path.of(spec.substring(7));
      return new Variant("script:" + file.getFileName(), Set.of(), KataScript.read(file));
    }
    throw new IllegalArgumentException(
        "Variant must be ai:<patches|none> or script:<file>: " + spec);
  }

  private static Set<ProAiPatch> parsePatches(final String value) {
    if (value.isBlank() || value.equalsIgnoreCase("none")) {
      return Set.of();
    }
    final Set<ProAiPatch> patches = EnumSet.noneOf(ProAiPatch.class);
    Arrays.stream(value.split(","))
        .map(String::trim)
        .map(ProAiPatch::valueOf)
        .forEach(patches::add);
    return patches;
  }

  // ---------------------------------------------------------------- output

  private static void printResult(final KataScore.Result r, final String player) {
    System.out.printf(
        "REP variant=%s seed=%d seconds=%.1f ended=%s%s%n",
        r.variant, r.seed, r.seconds, r.endedAt, r.gameOver ? " GAME_OVER" : "");
    System.out.printf(
        "  TUV gap (%s side - enemies): start=%d afterTurn=%d end=%d delta=%+d %s%n",
        player, r.gapStart, r.gapAfterTurn, r.gapEnd, r.gapDelta, signed(r.gapDeltaByKind));
    System.out.printf(
        "  side TUV %d->%d, enemy TUV %d->%d, side territory production %d->%d%n",
        r.sideTuvStart,
        r.sideTuvEnd,
        r.enemyTuvStart,
        r.enemyTuvEnd,
        r.sideProductionStart,
        r.sideProductionEnd);
    System.out.printf(
        "  victory cities %d->%d gained=%s lost=%s%n",
        r.victoryCitiesStart, r.victoryCitiesEnd, r.victoryCitiesGained, r.victoryCitiesLost);
    System.out.printf(
        "  territories gained=%s%n  territories lost=%s%n", r.territoriesGained, r.territoriesLost);
    System.out.printf("  capitals %s%n", r.capitals);
    System.out.println("  actions:");
    r.actions.forEach(a -> System.out.println("    " + a));
  }

  private static String signed(final Map<String, Integer> byKind) {
    return byKind.entrySet().stream()
        .map(e -> String.format("%s=%+d", e.getKey(), e.getValue()))
        .collect(Collectors.joining(" ", "[", "]"));
  }

  private record Metric(String name, ToDoubleFunction<KataScore.Result> value) {}

  private static final List<Metric> METRICS =
      List.of(
          new Metric("gapDelta", r -> r.gapDelta),
          new Metric("production", r -> r.gapDeltaByKind.get("production")),
          new Metric("combat", r -> r.gapDeltaByKind.get("combat")),
          new Metric("other", r -> r.gapDeltaByKind.get("other")),
          new Metric("turnDelta", r -> r.gapAfterTurn - r.gapStart),
          new Metric("terrProdNet", r -> r.territoryProductionNet),
          new Metric("vcNet", r -> r.victoryCitiesEnd - r.victoryCitiesStart),
          new Metric("capHeld", r -> r.testedCapitalHeld ? 1 : 0));

  private static void printSummary(final Map<String, List<KataScore.Result>> results) {
    System.out.println();
    System.out.println("SUMMARY mean ± sd [min..max]; gap = tested side TUV - enemy TUV");
    final String first = results.keySet().iterator().next();
    for (final Metric m : METRICS) {
      System.out.printf("  %-12s", m.name());
      for (final Map.Entry<String, List<KataScore.Result>> e : results.entrySet()) {
        final double[] xs = e.getValue().stream().mapToDouble(m.value()).toArray();
        System.out.printf(
            "  %s: %7.1f ± %5.1f [%.0f..%.0f]", e.getKey(), mean(xs), sd(xs), min(xs), max(xs));
      }
      System.out.println();
    }
    for (final Map.Entry<String, List<KataScore.Result>> e : results.entrySet()) {
      if (e.getKey().equals(first)) {
        continue;
      }
      System.out.printf("DIFF %s - %s (mean diff ± standard error):%n", e.getKey(), first);
      for (final Metric m : METRICS) {
        final double[] a = results.get(first).stream().mapToDouble(m.value()).toArray();
        final double[] b = e.getValue().stream().mapToDouble(m.value()).toArray();
        final double se = Math.sqrt(var(a) / a.length + var(b) / b.length);
        System.out.printf("  %-12s %+8.1f ± %.1f%n", m.name(), mean(b) - mean(a), se);
      }
    }
  }

  private static double mean(final double[] xs) {
    return Arrays.stream(xs).average().orElse(Double.NaN);
  }

  private static double var(final double[] xs) {
    if (xs.length < 2) {
      return 0;
    }
    final double mean = mean(xs);
    return Arrays.stream(xs).map(x -> (x - mean) * (x - mean)).sum() / (xs.length - 1);
  }

  private static double sd(final double[] xs) {
    return Math.sqrt(var(xs));
  }

  private static double min(final double[] xs) {
    return Arrays.stream(xs).min().orElse(Double.NaN);
  }

  private static double max(final double[] xs) {
    return Arrays.stream(xs).max().orElse(Double.NaN);
  }

  // ---------------------------------------------------------------- save

  private static void save(final Map<String, List<String>> opts) throws IOException {
    final Path out = Path.of(required(opts, "out"));
    if (opts.containsKey("from")) {
      final GameData data = load(Path.of(required(opts, "from")));
      applyEdit(data, Path.of(required(opts, "edit")));
      Files.createDirectories(out.toAbsolutePath().getParent());
      try (OutputStream os = Files.newOutputStream(out)) {
        GameDataManager.saveGame(os, data);
      }
      System.out.println("Wrote " + out.toAbsolutePath());
      return;
    }
    final String map = required(opts, "map");
    final int round = Integer.parseInt(required(opts, "round"));
    final String stepName = required(opts, "step");
    final ServerGame game =
        GameTestUtils.setUpGameWithAis(
            map,
            data -> {
              AiMatch.setProperty(data, Constants.TECH_DEVELOPMENT, "false");
              stepNamed(data, stepName);
            },
            p -> PlayerTypes.PRO_AI);
    game.setStopGameOnDelegateExecutionStop(true);
    if (opts.containsKey("seed")) {
      game.setRandomSource(new PlainRandomSource(Long.parseLong(one(opts, "seed", null))));
    }
    game.setUpGameForRunningSteps();
    final GameSequence sequence = game.getData().getSequence();
    while (!(sequence.getRound() == round && sequence.getStep().getName().equals(stepName))) {
      if (game.isGameOver() || sequence.getRound() > round) {
        throw new IllegalStateException("Passed round " + round + " without reaching " + stepName);
      }
      game.runNextStep();
    }
    game.saveGame(out.toAbsolutePath());
    System.out.println(
        "Wrote " + out.toAbsolutePath() + " at round " + round + " step " + stepName);
    game.stopGame();
  }

  /**
   * Edit file: {@code {"owners": {"T": "P"}, "removeUnits": [{"territory", "owner"?, "units"?}]}}.
   */
  private static void applyEdit(final GameData data, final Path editFile) throws IOException {
    final JsonObject edit = GSON.fromJson(Files.readString(editFile), JsonObject.class);
    final CompositeChange change = new CompositeChange();
    if (edit.has("owners")) {
      edit.getAsJsonObject("owners")
          .entrySet()
          .forEach(
              e ->
                  change.add(
                      ChangeFactory.changeOwner(
                          KataAi.territory(data, e.getKey()),
                          player(data, e.getValue().getAsString()))));
    }
    if (edit.has("removeUnits")) {
      for (final var element : edit.getAsJsonArray("removeUnits")) {
        final JsonObject r = element.getAsJsonObject();
        final Territory t = KataAi.territory(data, r.get("territory").getAsString());
        List<Unit> units = new ArrayList<>(t.getUnits());
        if (r.has("owner")) {
          units.removeIf(
              Matches.unitIsOwnedBy(player(data, r.get("owner").getAsString())).negate());
        }
        if (r.has("units")) {
          final List<Unit> picked = new ArrayList<>();
          for (final var u : r.getAsJsonObject("units").entrySet()) {
            final UnitType type = data.getUnitTypeList().getUnitTypeOrThrow(u.getKey());
            units.stream()
                .filter(Matches.unitIsOfType(type))
                .limit(u.getValue().getAsInt())
                .forEach(picked::add);
          }
          units = picked;
        }
        if (!units.isEmpty()) {
          change.add(ChangeFactory.removeUnits(t, units));
        }
      }
    }
    data.performChange(change);
  }

  // ---------------------------------------------------------------- dump

  private static void dump(final Map<String, List<String>> opts) throws IOException {
    final GameData data = load(Path.of(required(opts, "save")));
    final GameSequence sequence = data.getSequence();
    final GamePlayer stepPlayer = sequence.getStep().getPlayerId();
    final GamePlayer focus =
        opts.containsKey("player") ? player(data, one(opts, "player", null)) : stepPlayer;
    final JsonObject root = new JsonObject();
    root.addProperty("round", sequence.getRound());
    root.addProperty("step", sequence.getStep().getName());
    root.addProperty("stepPlayer", stepPlayer == null ? null : stepPlayer.getName());

    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final JsonObject players = new JsonObject();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      final JsonObject jp = new JsonObject();
      jp.addProperty("pus", p.getResources().getQuantity(pus));
      jp.add(
          "allies",
          names(
              data.getPlayerList().getPlayers().stream()
                  .filter(o -> !o.equals(p) && data.getRelationshipTracker().isAllied(p, o))
                  .toList()));
      jp.add(
          "atWarWith",
          names(
              data.getPlayerList().getPlayers().stream()
                  .filter(o -> data.getRelationshipTracker().isAtWar(p, o))
                  .toList()));
      jp.add(
          "capitals",
          strings(
              TerritoryAttachment.getAllCapitals(p, data.getMap()).stream()
                  .map(Territory::getName)
                  .toList()));
      jp.add("unitsToPlace", countByType(p.getUnits()));
      if (p.getProductionFrontier() != null) {
        final JsonObject buy = new JsonObject();
        for (final ProductionRule rule : p.getProductionFrontier().getRules()) {
          final String result =
              rule.getResults().keySet().stream()
                  .map(r -> r.getName())
                  .collect(Collectors.joining("+"));
          buy.addProperty(result, rule.getCosts().getInt(pus));
        }
        jp.add("buy", buy);
      }
      players.add(p.getName(), jp);
    }
    root.add("players", players);

    if (focus != null) {
      final JsonObject types = new JsonObject();
      for (final UnitType type : data.getUnitTypeList().getAllUnitTypes()) {
        final UnitAttachment ua = type.getUnitAttachment();
        final JsonObject jt = new JsonObject();
        jt.addProperty("attack", ua.getAttack(focus));
        jt.addProperty("defense", ua.getDefense(focus));
        jt.addProperty("movement", ua.getMovement(focus));
        types.add(type.getName(), jt);
      }
      root.addProperty("unitStatsFor", focus.getName());
      root.add("unitTypes", types);
    }

    final JsonArray territories = new JsonArray();
    for (final Territory t : data.getMap().getTerritories()) {
      final JsonObject jt = new JsonObject();
      jt.addProperty("name", t.getName());
      jt.addProperty("owner", t.getOwner().getName());
      jt.addProperty("water", t.isWater());
      final int production = TerritoryAttachment.getProduction(t);
      if (production > 0) {
        jt.addProperty("production", production);
      }
      TerritoryAttachment.get(t)
          .ifPresent(
              ta -> {
                if (ta.getVictoryCity() > 0) {
                  jt.addProperty("victoryCity", ta.getVictoryCity());
                }
                ta.getCapital().ifPresent(c -> jt.addProperty("capitalOf", c));
              });
      jt.add(
          "neighbors",
          strings(
              data.getMap().getNeighbors(t).stream().map(Territory::getName).sorted().toList()));
      final JsonObject units = new JsonObject();
      final Map<String, List<Unit>> byOwner = new TreeMap<>();
      t.getUnits()
          .forEach(
              u -> byOwner.computeIfAbsent(u.getOwner().getName(), k -> new ArrayList<>()).add(u));
      byOwner.forEach((owner, us) -> units.add(owner, countByType(us)));
      jt.add("units", units);
      territories.add(jt);
    }
    root.add("territories", territories);

    final String json = GSON.toJson(root);
    if (opts.containsKey("out")) {
      final Path out = Path.of(one(opts, "out", null));
      Files.writeString(out, json);
      System.out.println("Wrote " + out.toAbsolutePath());
    } else {
      System.out.println(json);
    }
  }

  private static JsonObject countByType(final Collection<Unit> units) {
    final Map<String, Integer> counts = new TreeMap<>();
    units.forEach(u -> counts.merge(u.getType().getName(), 1, Integer::sum));
    final JsonObject json = new JsonObject();
    counts.forEach(json::addProperty);
    return json;
  }

  private static JsonArray names(final List<GamePlayer> players) {
    return strings(players.stream().map(GamePlayer::getName).toList());
  }

  private static JsonArray strings(final List<String> values) {
    final JsonArray array = new JsonArray();
    values.forEach(array::add);
    return array;
  }

  // ---------------------------------------------------------------- helpers

  private static GameData load(final Path save) {
    return GameDataManager.loadGame(save)
        .orElseThrow(() -> new IllegalArgumentException("Cannot load save " + save));
  }

  private static GamePlayer player(final GameData data, final String name) {
    final GamePlayer p = data.getPlayerList().getPlayerId(name);
    if (p == null) {
      throw new IllegalArgumentException(
          "No player "
              + name
              + "; players: "
              + data.getPlayerList().getPlayers().stream().map(GamePlayer::getName).toList());
    }
    return p;
  }

  private static String required(final Map<String, List<String>> opts, final String key) {
    final String value = one(opts, key, null);
    if (value == null) {
      throw new IllegalArgumentException("Missing --" + key);
    }
    return value;
  }

  private static @Nullable String one(
      final Map<String, List<String>> opts, final String key, final @Nullable String fallback) {
    final List<String> values = opts.get(key);
    return values == null ? fallback : values.get(values.size() - 1);
  }

  private static Map<String, List<String>> parseArgs(final String[] args) {
    final Map<String, List<String>> opts = new TreeMap<>();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--") || i + 1 >= args.length) {
        throw new IllegalArgumentException("Expected --key value at: " + args[i]);
      }
      opts.computeIfAbsent(args[i].substring(2), k -> new ArrayList<>()).add(args[++i]);
    }
    return opts;
  }
}
