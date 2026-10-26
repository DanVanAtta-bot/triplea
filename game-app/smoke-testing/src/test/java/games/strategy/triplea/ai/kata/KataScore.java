package games.strategy.triplea.ai.kata;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.AbstractPlaceDelegate;
import games.strategy.triplea.delegate.PurchaseDelegate;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Scores a play-out from the tested player's side (it and its allies) against everyone at war with
 * it. TUV counts units on the map plus units bought but not yet placed, valued at each owner's TUV
 * cost. Each step's TUV change is booked by step type: purchase/place steps are production, battle
 * steps are combat, everything else (eg: air units that cannot land) is other.
 */
final class KataScore {
  private final GameData data;
  private final Set<String> side;
  private final Set<String> enemies;
  private final TuvCostsCalculator costs = new TuvCostsCalculator();
  private final Map<String, Integer> sideByKind = new TreeMap<>();
  private final Map<String, Integer> enemyByKind = new TreeMap<>();
  private final Map<String, List<String>> capitals = new TreeMap<>();
  private final Map<String, Boolean> capitalLostDuring = new TreeMap<>();
  private final Snapshot start;
  private Snapshot afterTurn;

  /** TUV per player and owner per territory at one moment. */
  record Snapshot(Map<String, Integer> tuv, Map<String, String> owners) {}

  KataScore(final GameData data, final GamePlayer tested) {
    this.data = data;
    side =
        data.getPlayerList().getPlayers().stream()
            .filter(p -> p.equals(tested) || data.getRelationshipTracker().isAllied(tested, p))
            .map(GamePlayer::getName)
            .collect(Collectors.toSet());
    enemies =
        data.getPlayerList().getPlayers().stream()
            .filter(p -> data.getRelationshipTracker().isAtWar(tested, p))
            .map(GamePlayer::getName)
            .collect(Collectors.toSet());
    for (final String name : side) {
      final GamePlayer player = data.getPlayerList().getPlayerId(name);
      final List<String> caps =
          TerritoryAttachment.getAllCapitals(player, data.getMap()).stream()
              .map(Territory::getName)
              .toList();
      if (!caps.isEmpty()) {
        capitals.put(name, caps);
        capitalLostDuring.put(name, false);
      }
    }
    start = snapshot();
    afterTurn = start;
  }

  Snapshot snapshot() {
    final Map<String, Integer> tuv = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit unit : t.getUnits()) {
        addTuv(tuv, unit);
      }
    }
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      for (final Unit unit : player.getUnits()) {
        addTuv(tuv, unit);
      }
    }
    final Map<String, String> owners = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      owners.put(t.getName(), t.getOwner().getName());
    }
    return new Snapshot(tuv, owners);
  }

  private void addTuv(final Map<String, Integer> tuv, final Unit unit) {
    final GamePlayer owner = unit.getOwner();
    if (!owner.isNull()) {
      tuv.merge(owner.getName(), costs.getCostsForTuv(owner).getInt(unit.getType()), Integer::sum);
    }
  }

  /** Books the TUV change of one step and notes any capital that fell. */
  void recordStep(final Snapshot before, final Snapshot after, final IDelegate delegate) {
    final String kind;
    if (delegate instanceof PurchaseDelegate || delegate instanceof AbstractPlaceDelegate) {
      kind = "production";
    } else if (delegate instanceof BattleDelegate) {
      kind = "combat";
    } else {
      kind = "other";
    }
    sideByKind.merge(kind, sum(after, side) - sum(before, side), Integer::sum);
    enemyByKind.merge(kind, sum(after, enemies) - sum(before, enemies), Integer::sum);
    capitals.forEach(
        (player, caps) -> {
          if (caps.stream().noneMatch(c -> player.equals(after.owners().get(c)))) {
            capitalLostDuring.put(player, true);
          }
        });
  }

  void markEndOfTestedTurn() {
    afterTurn = snapshot();
  }

  private static int sum(final Snapshot s, final Set<String> players) {
    return players.stream().mapToInt(p -> s.tuv().getOrDefault(p, 0)).sum();
  }

  private int gap(final Snapshot s) {
    return sum(s, side) - sum(s, enemies);
  }

  /** One repetition's result; also the JSON shape written by {@code --out}. */
  static final class Result {
    String variant;
    long seed;
    double seconds;
    String endedAt;
    boolean gameOver;
    int gapStart;
    int gapAfterTurn;
    int gapEnd;
    int gapDelta;
    Map<String, Integer> gapDeltaByKind = new TreeMap<>();
    int sideTuvStart;
    int sideTuvEnd;
    int enemyTuvStart;
    int enemyTuvEnd;
    int sideProductionStart;
    int sideProductionEnd;
    int victoryCitiesStart;
    int victoryCitiesEnd;
    List<String> victoryCitiesGained = new ArrayList<>();
    List<String> victoryCitiesLost = new ArrayList<>();
    List<String> territoriesGained = new ArrayList<>();
    List<String> territoriesLost = new ArrayList<>();
    int territoryProductionNet;
    Map<String, String> capitals = new TreeMap<>();
    boolean testedCapitalHeld;
    List<String> actions = new ArrayList<>();
  }

  Result result(final String testedPlayer) {
    final Snapshot end = snapshot();
    final Result r = new Result();
    r.gapStart = gap(start);
    r.gapAfterTurn = gap(afterTurn);
    r.gapEnd = gap(end);
    r.gapDelta = r.gapEnd - r.gapStart;
    for (final String kind : List.of("production", "combat", "other")) {
      r.gapDeltaByKind.put(
          kind, sideByKind.getOrDefault(kind, 0) - enemyByKind.getOrDefault(kind, 0));
    }
    r.sideTuvStart = sum(start, side);
    r.sideTuvEnd = sum(end, side);
    r.enemyTuvStart = sum(start, enemies);
    r.enemyTuvEnd = sum(end, enemies);
    for (final Territory t : data.getMap().getTerritories()) {
      final String name = t.getName();
      final boolean before = side.contains(start.owners().get(name));
      final boolean after = side.contains(end.owners().get(name));
      final int production = TerritoryAttachment.getProduction(t);
      final int vc =
          TerritoryAttachment.get(t).map(TerritoryAttachment::getVictoryCity).orElse(0);
      r.sideProductionStart += before ? production : 0;
      r.sideProductionEnd += after ? production : 0;
      r.victoryCitiesStart += before ? vc : 0;
      r.victoryCitiesEnd += after ? vc : 0;
      if (before != after) {
        final String label =
            name + (production > 0 ? " (" + production + ")" : "") + (vc > 0 ? " [VC]" : "");
        (after ? r.territoriesGained : r.territoriesLost).add(label);
        if (vc > 0) {
          (after ? r.victoryCitiesGained : r.victoryCitiesLost).add(name);
        }
        r.territoryProductionNet += after ? production : -production;
      }
    }
    capitals.forEach(
        (player, caps) -> {
          final boolean held = caps.stream().anyMatch(c -> player.equals(end.owners().get(c)));
          final String state =
              held ? (capitalLostDuring.get(player) ? "retaken" : "held") : "lost";
          r.capitals.put(player + " " + caps, state);
          if (player.equals(testedPlayer)) {
            r.testedCapitalHeld = held && !capitalLostDuring.get(player);
          }
        });
    return r;
  }
}
