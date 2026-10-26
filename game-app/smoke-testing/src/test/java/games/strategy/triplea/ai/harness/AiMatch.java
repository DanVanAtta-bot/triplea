package games.strategy.triplea.ai.harness;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.gameparser.GameParser;
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

/**
 * Plays headless all-Hard-AI games where one alliance runs a patched Hard AI and every other player
 * runs the unmodified one. Prints one RESULT line per game.
 *
 * <p>Args: {@code --map <xml> --patched <alliance> --patches <P1,P2|none> --rounds <cap> [--calc
 * bounded|concurrent] [--swap] [--out <dir>] [--odds-runs <n>] [--flag-combat <tuv>] [--flag-odds
 * <0..1>]}. {@code --swap} plays a second game with the patched set given to the other alliance
 * (two-alliance maps only). Every game writes {@code <out>/<game id>/} with {@code game.json},
 * {@code records.jsonl} and the saves of flagged rounds; see {@link GameRecorder}.
 */
public final class AiMatch {

  private AiMatch() {}

  public static void main(final String[] args) throws Exception {
    final Map<String, String> opts = parseArgs(args);
    final String map = opts.getOrDefault("map", "big_world_1942.xml");
    final int roundCap = Integer.parseInt(opts.getOrDefault("rounds", "30"));
    final Set<ProAiPatch> patches = parsePatches(opts.getOrDefault("patches", "NO_OP"));
    final String patchedAlliance = opts.getOrDefault("patched", "Axis");

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
    runConfig.put("tech_development", false);
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
    final List<String> orientations = new ArrayList<>(List.of(patchedAlliance));
    if (opts.containsKey("swap")) {
      orientations.add(otherAlliance(map, patchedAlliance));
    }
    for (final String alliance : orientations) {
      playOne(map, roundCap, patches, alliance, outRoot, recorderOptions, runConfig);
    }
    // Game threads (battle calculator workers etc.) are not all daemons.
    System.exit(0);
  }

  private static void playOne(
      final String map,
      final int roundCap,
      final Set<ProAiPatch> patches,
      final String patchedAlliance,
      final Path outRoot,
      final GameRecorder.Options recorderOptions,
      final Map<String, Object> runConfig) {
    final List<ProAi> ais = new ArrayList<>();
    final DecisionLog decisionLog = new DecisionLog();
    final PlayerTypes.Type patched = proAiType("Hard AI (patched)", patches, ais, decisionLog);
    final PlayerTypes.Type baseline = proAiType("Hard AI (baseline)", Set.of(), ais, decisionLog);
    final long start = System.nanoTime();
    final ServerGame game =
        GameTestUtils.setUpGameWithAis(
            map,
            gameData -> {
              requireAlliance(gameData, patchedAlliance);
              gameData.getProperties().set(Constants.TECH_DEVELOPMENT, false);
            },
            player -> allianceOf(player).equals(patchedAlliance) ? patched : baseline);
    game.setStopGameOnDelegateExecutionStop(true);
    final GameData data = game.getData();
    final Map<String, String> variantBySide = new LinkedHashMap<>();
    final Map<String, List<String>> patchesBySide = new LinkedHashMap<>();
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      final String side = allianceOf(player);
      final boolean isPatched = side.equals(patchedAlliance) && !patches.isEmpty();
      variantBySide.put(side, isPatched ? "patched" : "baseline");
      patchesBySide.put(
          side, isPatched ? patches.stream().map(Enum::name).sorted().toList() : List.of());
    }
    final Map<String, Object> config = new LinkedHashMap<>(runConfig);
    config.put("patched_alliance", patchedAlliance);
    config.put("patches_by_side", patchesBySide);
    config.put("started_at", Instant.now().toString());
    final String gameId =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
            + "-"
            + UUID.randomUUID().toString().substring(0, 6);
    final GameRecorder recorder =
        new GameRecorder(
            game, outRoot, gameId, recorderOptions, decisionLog, variantBySide, config);
    System.out.println("RECORDS " + recorder.dir());
    while (!game.isGameOver() && data.getSequence().getRound() <= roundCap) {
      recorder.beforeStep();
      game.runNextStep();
      recorder.afterStep();
    }
    final double seconds = (System.nanoTime() - start) / 1e9;
    final boolean over = game.isGameOver();
    final Collection<GamePlayer> winners =
        over ? ((EndRoundDelegate) data.getEndRoundDelegate()).getWinners() : List.of();
    final String winnerAlliance =
        winners.stream().map(AiMatch::allianceOf).distinct().collect(Collectors.joining("+"));
    final Map<String, Object> result = new LinkedHashMap<>();
    result.put("winner", winners.isEmpty() ? null : winnerAlliance);
    result.put("winners", winners.stream().map(GamePlayer::getName).sorted().toList());
    result.put("rounds", over ? data.getSequence().getRound() : data.getSequence().getRound() - 1);
    result.put("capped", !over);
    result.put("seconds", Math.round(seconds * 10) / 10.0);
    result.put("victory_cities", victoryCities(data));
    result.put("patch_hits", patchHits(ais));
    recorder.finish(result);
    System.out.printf(
        "RESULT map=%s patched=%s patches=%s winner=%s winners=%s rounds=%d capped=%s"
            + " seconds=%.1f victoryCities=%s patchHits=%s%n",
        map,
        patchedAlliance,
        patches,
        winners.isEmpty() ? "none" : winnerAlliance,
        winners.stream().map(GamePlayer::getName).sorted().toList(),
        over ? data.getSequence().getRound() : data.getSequence().getRound() - 1,
        !over,
        seconds,
        victoryCities(data),
        patchHits(ais));
    if (!over) {
      // Resets ProAi's static battle calculator so a following game in this JVM starts clean.
      game.stopGame();
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

  private static Map<String, Map<ProAiPatch, Integer>> patchHits(final List<ProAi> ais) {
    final Map<String, Map<ProAiPatch, Integer>> hits = new TreeMap<>();
    for (final ProAi ai : ais) {
      if (!ai.getProData().getPatches().isEmpty()) {
        hits.put(ai.getName(), ai.getProData().getPatchHits());
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

  private static String allianceOf(final GamePlayer player) {
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
