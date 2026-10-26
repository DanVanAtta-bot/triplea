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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Plays headless all-Hard-AI games where one alliance runs a patched Hard AI and every other player
 * runs the unmodified one. Prints one RESULT line per game.
 *
 * <p>Args: {@code --map <xml> --patched <alliance> --patches <P1,P2|none> --rounds <cap> [--calc
 * bounded|concurrent] [--swap]}. {@code --swap} plays a second game with the patched set given to
 * the other alliance (two-alliance maps only).
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
    GameTestUtils.setUp();
    // ProAi resolves its static calculator once per JVM, on first use, from this setting.
    ClientSetting.useBoundedContextBattleCalc.setValue(calc.equals("bounded"));
    System.out.println("CALC " + calc);
    final List<String> orientations = new ArrayList<>(List.of(patchedAlliance));
    if (opts.containsKey("swap")) {
      orientations.add(otherAlliance(map, patchedAlliance));
    }
    for (final String alliance : orientations) {
      playOne(map, roundCap, patches, alliance);
    }
    // Game threads (battle calculator workers etc.) are not all daemons.
    System.exit(0);
  }

  private static void playOne(
      final String map,
      final int roundCap,
      final Set<ProAiPatch> patches,
      final String patchedAlliance) {
    final List<ProAi> ais = new ArrayList<>();
    final PlayerTypes.Type patched = proAiType("Hard AI (patched)", patches, ais);
    final PlayerTypes.Type baseline = proAiType("Hard AI (baseline)", Set.of(), ais);
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
    while (!game.isGameOver() && data.getSequence().getRound() <= roundCap) {
      game.runNextStep();
    }
    final double seconds = (System.nanoTime() - start) / 1e9;
    final boolean over = game.isGameOver();
    final Collection<GamePlayer> winners =
        over ? ((EndRoundDelegate) data.getEndRoundDelegate()).getWinners() : List.of();
    final String winnerAlliance =
        winners.stream().map(AiMatch::allianceOf).distinct().collect(Collectors.joining("+"));
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
      final String label, final Set<ProAiPatch> patches, final List<ProAi> created) {
    return new PlayerTypes.Type(label, false) {
      @Override
      public Player newPlayerWithName(final String name) {
        final ProAi ai = new ProAi(name, getLabel(), patches);
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
    final Collection<String> alliances =
        player.getData().getAllianceTracker().getAlliancesPlayerIsIn(player);
    return alliances.isEmpty() ? player.getName() : String.join("+", alliances);
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
