package games.strategy.triplea.ai.harness;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameSequence;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.engine.random.PlainRandomSource;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.ProAiPatchSpec;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.settings.ClientSetting;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Bid kata runner (ai-mechanics): plays the first round(s) of a map from its start K times with a
 * bid (a human placement via {@code --bid}, or the map's own bid steps via {@code --props "Germans
 * bid=9"} so ProAI spends and places it), and follows every bid unit by UUID.
 *
 * <p>Args: {@code --map <xml> [--bid <bid.json>] [--props "k=v,..."] --patched <alliance> --patches
 * <P,P|none> --baseline-patches <P,P|none> --reps k --seed s --rounds n --out <json.gz>}.
 *
 * <p>Per rep: the bid units (owner, type, placed territory); for each player's turn the bid units'
 * location after its combat move, which of them attacked (ended combat move in an enemy-owned land
 * territory or a sea zone with enemy units), whether they survived the battle step and whether the
 * territory then belonged to their side; battle losses (TUV) by side per battle step; captures per
 * turn; and side TUV / territory production after the bids and after every turn.
 */
public final class BidRun {
  private BidRun() {}

  public static void main(final String[] args) throws Exception {
    final Map<String, String> o = new HashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      o.put(args[i].replaceFirst("^--", ""), args[i + 1]);
    }
    GameTestUtils.setUp();
    ClientSetting.useBoundedContextBattleCalc.setValue(
        o.getOrDefault("calc", "bounded").equals("bounded"));
    final int reps = Integer.parseInt(o.getOrDefault("reps", "1"));
    final long seed = Long.parseLong(o.getOrDefault("seed", "1"));
    final JsonObject root = new JsonObject();
    o.forEach(root::addProperty);
    final JsonArray out = new JsonArray();
    for (int i = 0; i < reps; i++) {
      final JsonObject rep = playOne(o, seed + i);
      System.out.printf("BIDREP %d seed=%d seconds=%s%n", i, seed + i, rep.get("seconds"));
      out.add(rep);
    }
    root.add("reps", out);
    final Path outPath = Path.of(o.get("out"));
    Files.createDirectories(outPath.toAbsolutePath().getParent());
    try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(outPath));
        Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
      new Gson().toJson(root, w);
    }
    System.out.println("BIDRUN wrote " + outPath);
    System.exit(0);
  }

  private static JsonObject playOne(final Map<String, String> o, final long seed) throws Exception {
    final long t0 = System.nanoTime();
    final String map = o.get("map");
    final FixedBid bid = o.containsKey("bid") ? FixedBid.load(Path.of(o.get("bid"))) : null;
    final Map<String, String> props = new TreeMap<>();
    props.put(Constants.TECH_DEVELOPMENT, "false");
    for (final String kv : o.getOrDefault("props", "").split(",")) {
      if (kv.contains("=")) {
        props.put(
            kv.substring(0, kv.indexOf('=')).trim(), kv.substring(kv.indexOf('=') + 1).trim());
      }
    }
    final String patchedAlliance = o.getOrDefault("patched", "Axis");
    final List<ProAiPatchSpec> patches = parsePatches(o.getOrDefault("patches", "none"));
    final List<ProAiPatchSpec> baseline = parsePatches(o.getOrDefault("baseline-patches", "none"));
    final List<ProAi> ais = new ArrayList<>();
    final PlayerTypes.Type patchedType = type("Hard AI (bid patched)", patches, ais);
    final PlayerTypes.Type baselineType = type("Hard AI (bid baseline)", baseline, ais);
    final Set<UUID> preBid = new HashSet<>();
    final ServerGame game =
        GameTestUtils.setUpGameWithAis(
            map,
            data -> {
              props.forEach((k, v) -> AiMatch.setProperty(data, k, v));
              for (final Territory t : data.getMap().getTerritories()) {
                t.getUnits().forEach(u -> preBid.add(u.getId()));
              }
              if (bid != null) {
                bid.apply(data, map);
              }
            },
            p ->
                p.getData().getAllianceTracker().getAlliancesPlayerIsIn(p).contains(patchedAlliance)
                    ? patchedType
                    : baselineType);
    game.setStopGameOnDelegateExecutionStop(true);
    game.setRandomSource(new PlainRandomSource(seed));
    final GameData data = game.getData();
    final GameSequence seq = data.getSequence();
    final Map<String, Integer> costs = costs(data);
    final int rounds = Integer.parseInt(o.getOrDefault("rounds", "1"));
    final JsonObject rep = new JsonObject();
    rep.addProperty("seed", seed);
    final JsonArray turns = new JsonArray();
    final JsonArray battles = new JsonArray();
    Map<UUID, String[]> tagged = null; // id -> owner, type, placed territory
    game.setUpGameForRunningSteps();
    JsonObject turn = null;
    Map<String, String> ownersBeforeBattle = null;
    Map<UUID, Unit> unitsBeforeBattle = null;
    final Map<UUID, String> attackedFrom = new HashMap<>();
    while (!game.isGameOver()) {
      final GameStep step = seq.getStep();
      final String name = step.getName();
      final GamePlayer player = step.getPlayerId();
      final boolean bidStep = GameStep.isBidStepName(name) || GameStep.isBidPlaceStepName(name);
      if (seq.getRound() > rounds) {
        break;
      }
      if (tagged == null && player != null && !bidStep) {
        tagged = new LinkedHashMap<>();
        final JsonArray bidUnits = new JsonArray();
        for (final Territory t : data.getMap().getTerritories()) {
          for (final Unit u : t.getUnits()) {
            if (!preBid.contains(u.getId())) {
              tagged.put(
                  u.getId(),
                  new String[] {u.getOwner().getName(), u.getType().getName(), t.getName()});
              final JsonObject b = new JsonObject();
              b.addProperty("id", u.getId().toString().substring(0, 8));
              b.addProperty("owner", u.getOwner().getName());
              b.addProperty("type", u.getType().getName());
              b.addProperty("territory", t.getName());
              bidUnits.add(b);
            }
          }
        }
        rep.add("bid_units", bidUnits);
        rep.add("after_bid", summary(data, costs, "after_bid"));
      }
      Map<String, String> ownersBeforeMove = null;
      if (GameStep.isCombatMoveStepName(name)) {
        ownersBeforeMove = owners(data);
      }
      if (GameStep.isBattleStepName(name)) {
        ownersBeforeBattle = owners(data);
        unitsBeforeBattle = allUnits(data);
      }
      if (player != null && !bidStep && GameStep.isPurchaseStepName(name)) {
        turn = new JsonObject();
        turn.addProperty("round", seq.getRound());
        turn.addProperty("player", player.getName());
        turn.addProperty("pus_start", pus(data, player));
        turns.add(turn);
      }
      game.runNextStep();
      if (tagged == null || turn == null || player == null || bidStep) {
        continue;
      }
      if (GameStep.isCombatMoveStepName(name)) {
        final JsonArray blitzed = new JsonArray();
        final Map<String, String> ownersAfterMove = owners(data);
        attackedFrom.clear();
        for (final Territory t : data.getMap().getTerritories()) {
          for (final Unit u : t.getUnits()) {
            final String[] info = tagged.get(u.getId());
            if (info == null || !u.getOwner().equals(player)) {
              continue;
            }
            final boolean enemyHere =
                t.isWater()
                    ? t.getUnits().stream().anyMatch(x -> player.isAtWar(x.getOwner()))
                    : player.isAtWar(t.getOwner())
                        || (t.getOwner().isNull() && !t.getUnits().isEmpty());
            if (enemyHere) {
              attackedFrom.put(u.getId(), t.getName());
            } else if (!t.isWater()
                && ownersBeforeMove != null
                && !ownersAfterMove.get(t.getName()).equals(ownersBeforeMove.get(t.getName()))) {
              final JsonObject b = new JsonObject();
              b.addProperty("type", info[1]);
              b.addProperty("placed", info[2]);
              b.addProperty("target", t.getName());
              blitzed.add(b);
            }
          }
        }
        turn.addProperty("bid_units_attacking", attackedFrom.size());
        turn.add("bid_blitzed", blitzed);
      }
      if (GameStep.isBattleStepName(name) && ownersBeforeBattle != null) {
        final Map<String, String> after = owners(data);
        final Map<UUID, Unit> unitsAfter = allUnits(data);
        final JsonObject lost = new JsonObject();
        final Map<String, Integer> lostBySide = new TreeMap<>();
        for (final Map.Entry<UUID, Unit> e : unitsBeforeBattle.entrySet()) {
          if (!unitsAfter.containsKey(e.getKey())) {
            final Unit u = e.getValue();
            lostBySide.merge(
                sideOf(data, u.getOwner()),
                costs.getOrDefault(u.getType().getName(), 0),
                Integer::sum);
          }
        }
        lostBySide.forEach(lost::addProperty);
        turn.add("battle_lost_tuv", lost);
        final JsonArray caps = new JsonArray();
        final Map<String, String> before = ownersBeforeBattle;
        after.forEach(
            (t, ow) -> {
              if (!ow.equals(before.get(t))) {
                caps.add(t);
              }
            });
        turn.add("captured", caps);
        final JsonArray used = new JsonArray();
        for (final Map.Entry<UUID, String> e : attackedFrom.entrySet()) {
          final String[] info = tagged.get(e.getKey());
          final JsonObject a = new JsonObject();
          a.addProperty("id", e.getKey().toString().substring(0, 8));
          a.addProperty("type", info[1]);
          a.addProperty("placed", info[2]);
          a.addProperty("target", e.getValue());
          a.addProperty("survived", unitsAfter.containsKey(e.getKey()));
          final String ow = after.get(e.getValue());
          a.addProperty(
              "target_won",
              ow != null
                  && !ow.equals(ownersBeforeBattle.get(e.getValue()))
                  && !player.isAtWar(data.getPlayerList().getPlayerId(ow)));
          used.add(a);
        }
        turn.add("bid_attacks", used);
        ownersBeforeBattle = null;
      }
      if (GameStep.isEndTurnStepName(name)) {
        final JsonArray alive = new JsonArray();
        final Map<UUID, Unit> now = allUnits(data);
        for (final Map.Entry<UUID, String[]> e : tagged.entrySet()) {
          final Unit u = now.get(e.getKey());
          if (u != null && u.getOwner().getName().equals(e.getValue()[0])) {
            alive.add(e.getKey().toString().substring(0, 8));
          }
        }
        turn.add("bid_alive", alive);
        turn.add("after", summary(data, costs, player.getName()));
      }
    }
    rep.add("turns", turns);
    rep.add("battles", battles);
    final JsonObject hits = new JsonObject();
    for (final ProAi ai : ais) {
      final JsonObject h = new JsonObject();
      ai.getProData().getPatchHits().forEach((k, v) -> h.addProperty(k.name(), v));
      hits.add(ai.getName(), h);
    }
    rep.add("patch_hits", hits);
    rep.addProperty("seconds", Math.round((System.nanoTime() - t0) / 1e8) / 10.0);
    game.stopGame();
    return rep;
  }

  private static String sideOf(final GameData data, final GamePlayer p) {
    final var a = data.getAllianceTracker().getAlliancesPlayerIsIn(p);
    return a.isEmpty() ? p.getName() : String.join("+", a);
  }

  private static int pus(final GameData data, final GamePlayer p) {
    return p.getResources().getQuantity(data.getResourceList().getResourceOrThrow(Constants.PUS));
  }

  private static Map<String, String> owners(final GameData data) {
    final Map<String, String> m = new TreeMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        m.put(t.getName(), t.getOwner().getName());
      }
    }
    return m;
  }

  private static Map<UUID, Unit> allUnits(final GameData data) {
    final Map<UUID, Unit> m = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      t.getUnits().forEach(u -> m.put(u.getId(), u));
    }
    return m;
  }

  private static JsonObject summary(
      final GameData data, final Map<String, Integer> costs, final String label) {
    final JsonObject s = new JsonObject();
    s.addProperty("label", label);
    final Map<String, Integer> tuv = new TreeMap<>();
    final Map<String, Integer> prod = new TreeMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnits()) {
        final String n = u.getType().getName();
        if (n.equals("factory") || n.equals("aaGun")) {
          continue;
        }
        tuv.merge(sideOf(data, u.getOwner()), costs.getOrDefault(n, 0), Integer::sum);
      }
      if (!t.isWater() && !t.getOwner().isNull()) {
        final int p = TerritoryAttachment.getProduction(t);
        prod.merge(sideOf(data, t.getOwner()), p, Integer::sum);
      }
    }
    final JsonObject jt = new JsonObject();
    tuv.forEach(jt::addProperty);
    s.add("tuv", jt);
    final JsonObject jp = new JsonObject();
    prod.forEach(jp::addProperty);
    s.add("production", jp);
    final JsonObject ow = new JsonObject();
    owners(data).forEach(ow::addProperty);
    s.add("owners", ow);
    final JsonObject pu = new JsonObject();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      pu.addProperty(p.getName(), pus(data, p));
    }
    s.add("pus", pu);
    return s;
  }

  private static Map<String, Integer> costs(final GameData data) {
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final Map<String, Integer> costs = new TreeMap<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (p.getProductionFrontier() == null) {
        continue;
      }
      for (final ProductionRule rule : p.getProductionFrontier().getRules()) {
        if (rule.getResults().size() != 1) {
          continue;
        }
        final int n = rule.getResults().totalValues();
        rule.getResults()
            .keySet()
            .forEach(
                r ->
                    costs.putIfAbsent(
                        r.getName(), Math.round((float) rule.getCosts().getInt(pus) / n)));
      }
    }
    return costs;
  }

  private static PlayerTypes.Type type(
      final String label, final List<ProAiPatchSpec> patches, final List<ProAi> created) {
    return new PlayerTypes.Type(label, false) {
      @Override
      public Player newPlayerWithName(final String name) {
        final ProAi ai = ProAi.withPatchSpecs(name, getLabel(), patches);
        created.add(ai);
        return ai;
      }
    };
  }

  private static List<ProAiPatchSpec> parsePatches(final String value) {
    if (value == null || value.isBlank() || value.equalsIgnoreCase("none")) {
      return List.of();
    }
    return ProAiPatchSpec.parseList(value);
  }
}
