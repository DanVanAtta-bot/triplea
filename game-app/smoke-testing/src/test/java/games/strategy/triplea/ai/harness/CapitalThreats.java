package games.strategy.triplea.ai.harness;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TerritoryEffectHelper;
import games.strategy.triplea.odds.calculator.AggregateResults;
import games.strategy.triplea.odds.calculator.ConcurrentBattleCalculator;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * At a player's turn end, estimates for every capital the strongest attack each enemy player could
 * make on its next turn, against the capital's current defenders.
 *
 * <p>Reach is a cheap over-estimate, not a move validation: a land unit counts if its land distance
 * (through passable land not holding units at war with it; neutrals rules and canals ignored) is at
 * most its movement; an air unit if its distance is at most movement - 1 (one move kept to land);
 * land cargo already at sea if its sea zone is within 3 (transport move 2 + unload); land units
 * that one of the attacker's own transports can load this turn (see {@link #amphibiousUnits}). Sea
 * units and bombardment are left out. Only threats with a land attacker are run through the
 * calculator, and a result is reused while the attacking and defending unit sets are unchanged.
 */
final class CapitalThreats {

  private static final int MAX_RANGE = 8;
  private static final int CARGO_RANGE = 3;
  private static final int MAX_TRANSPORT_MOVE = 4;

  private record Cached(Set<UUID> attackers, Set<UUID> defenders, Map<String, Object> odds) {}

  private final GameData data;
  private final ConcurrentBattleCalculator oddsCalc;
  private final int oddsRuns;
  private final Function<GamePlayer, String> sideOf;
  private final Function<Collection<Unit>, Integer> tuvOf;
  private final List<GamePlayer> turnOrder;
  private final Map<String, Cached> cache = new HashMap<>();
  // Per round: wall_ms, cpu_ms, calcs, cache_hits, batches; plus the round's whole wall and cpu.
  private final Map<Integer, Map<String, Object>> costByRound = new TreeMap<>();
  private long roundStartWall = System.nanoTime();
  private long roundStartCpu = processCpuNanos();

  CapitalThreats(
      final GameData data,
      final ConcurrentBattleCalculator oddsCalc,
      final int oddsRuns,
      final Function<GamePlayer, String> sideOf,
      final Function<Collection<Unit>, Integer> tuvOf,
      final List<GamePlayer> turnOrder) {
    this.data = data;
    this.oddsCalc = oddsCalc;
    this.oddsRuns = oddsRuns;
    this.sideOf = sideOf;
    this.tuvOf = tuvOf;
    this.turnOrder = turnOrder;
  }

  /** One {@code capital_threat} record per capital whose holder belongs to a side. */
  List<Map<String, Object>> afterTurn(
      final String gameId, final int round, final GamePlayer turnPlayer) {
    final long wall0 = System.nanoTime();
    final long cpu0 = processCpuNanos();
    int calcs = 0;
    int hits = 0;
    boolean calcReady = false;
    final List<Map<String, Object>> records = new ArrayList<>();
    for (final Territory capital : capitals()) {
      final GamePlayer owner = capital.getOwner();
      final String ownerSide = sideOf.apply(owner);
      if (ownerSide == null) {
        continue;
      }
      final Map<Territory, Integer> anyDist = distances(capital, null);
      final List<Map<String, Object>> threats = new ArrayList<>();
      for (final GamePlayer enemy : turnOrder) {
        if (!data.getRelationshipTracker().isAtWar(owner, enemy)) {
          continue;
        }
        final List<Unit> attackers = reachingUnits(enemy, distances(capital, enemy), anyDist);
        final List<Unit> amphibious = amphibiousUnits(enemy, capital, attackers);
        attackers.addAll(amphibious);
        if (attackers.isEmpty()) {
          continue;
        }
        final List<Unit> defenders = capital.getMatches(Matches.enemyUnit(enemy));
        final boolean hasLand = attackers.stream().anyMatch(Matches.unitIsLand());
        final Map<String, Object> threat = new LinkedHashMap<>();
        threat.put("attacker", enemy.getName());
        threat.put("attacker_side", sideOf.apply(enemy));
        threat.put("moves_before_owner", movesBefore(turnPlayer, enemy, owner));
        threat.put("attackers", countByType(attackers));
        threat.put("amphibious", countByType(amphibious));
        threat.put("attacker_tuv", tuvOf.apply(attackers));
        threat.put("has_land_attackers", hasLand);
        final Map<String, Object> odds;
        if (!hasLand) {
          odds = null;
        } else if (defenders.isEmpty()) {
          odds = Map.of("win", 1.0, "exp_tuv_swing", 0.0, "runs", 0);
        } else {
          final String key = capital.getName() + "|" + enemy.getName();
          final Set<UUID> attIds = ids(attackers);
          final Set<UUID> defIds = ids(defenders);
          final Cached cached = cache.get(key);
          if (cached != null
              && cached.attackers().equals(attIds)
              && cached.defenders().equals(defIds)) {
            odds = cached.odds();
            hits++;
          } else {
            if (!calcReady) {
              oddsCalc.setGameData(data).join();
              calcReady = true;
            }
            odds = calculate(enemy, owner, capital, attackers, defenders);
            cache.put(key, new Cached(attIds, defIds, odds));
            calcs++;
          }
        }
        threat.put("odds", odds);
        threats.add(threat);
      }
      threats.sort(Comparator.comparingDouble((Map<String, Object> th) -> -winOf(th)));
      final List<Unit> allDefenders = capital.getMatches(Matches.isUnitAllied(owner));
      final Map<String, Object> r = new LinkedHashMap<>();
      r.put("type", "capital_threat");
      r.put("game_id", gameId);
      r.put("round", round);
      r.put("after_turn_of", turnPlayer.getName());
      r.put("territory", capital.getName());
      r.put(
          "capital_of",
          TerritoryAttachment.get(capital).flatMap(TerritoryAttachment::getCapital).orElse(null));
      r.put("owner", owner.getName());
      r.put("owner_side", ownerSide);
      r.put("defenders", countByType(allDefenders));
      r.put("defender_tuv", tuvOf.apply(allDefenders));
      final Map<String, Object> top = threats.isEmpty() ? null : threats.get(0);
      r.put("max_win", top == null || top.get("odds") == null ? 0.0 : winOf(top));
      r.put("max_attacker", top == null ? null : top.get("attacker"));
      r.put("threats", threats);
      records.add(r);
    }
    final Map<String, Object> cost = costByRound.computeIfAbsent(round, k -> newCost());
    cost.merge("wall_ms", (System.nanoTime() - wall0) / 1_000_000L, (a, b) -> (Long) a + (Long) b);
    cost.merge("cpu_ms", (processCpuNanos() - cpu0) / 1_000_000L, (a, b) -> (Long) a + (Long) b);
    cost.merge("calcs", (long) calcs, (a, b) -> (Long) a + (Long) b);
    cost.merge("cache_hits", (long) hits, (a, b) -> (Long) a + (Long) b);
    cost.merge("batches", 1L, (a, b) -> (Long) a + (Long) b);
    return records;
  }

  /** Closes the round's cost entry with the whole round's wall and process CPU time. */
  void roundEnded(final int round) {
    final long wall = System.nanoTime();
    final long cpu = processCpuNanos();
    final Map<String, Object> cost = costByRound.computeIfAbsent(round, k -> newCost());
    cost.put("round_wall_ms", (wall - roundStartWall) / 1_000_000L);
    cost.put("round_cpu_ms", (cpu - roundStartCpu) / 1_000_000L);
    roundStartWall = wall;
    roundStartCpu = cpu;
  }

  Map<Integer, Map<String, Object>> costByRound() {
    return costByRound;
  }

  private static Map<String, Object> newCost() {
    final Map<String, Object> cost = new LinkedHashMap<>();
    for (final String k : List.of("wall_ms", "cpu_ms", "calcs", "cache_hits", "batches")) {
      cost.put(k, 0L);
    }
    return cost;
  }

  private Map<String, Object> calculate(
      final GamePlayer attacker,
      final GamePlayer defender,
      final Territory t,
      final List<Unit> attackers,
      final List<Unit> defenders) {
    oddsCalc.setAmphibious(false);
    final AggregateResults results =
        oddsCalc.calculate(
            attacker,
            defender,
            t,
            attackers,
            defenders,
            List.of(),
            TerritoryEffectHelper.getEffects(t),
            false,
            oddsRuns);
    final Map<String, Object> odds = new LinkedHashMap<>();
    if (results.getRollCount() > 0) {
      odds.put("win", round4(results.getAttackerWinPercent()));
      odds.put(
          "exp_tuv_swing",
          round4(results.getAverageTuvSwing(attacker, attackers, defender, defenders, data)));
    }
    odds.put("runs", results.getRollCount());
    return odds;
  }

  private static double winOf(final Map<String, Object> threat) {
    @SuppressWarnings("unchecked")
    final Map<String, Object> odds = (Map<String, Object>) threat.get("odds");
    return odds == null || odds.get("win") == null ? 0.0 : (Double) odds.get("win");
  }

  /** True if {@code enemy} gets a turn after {@code justMoved} before {@code owner} does. */
  private boolean movesBefore(
      final GamePlayer justMoved, final GamePlayer enemy, final GamePlayer owner) {
    final int start = turnOrder.indexOf(justMoved);
    for (int i = 1; i <= turnOrder.size(); i++) {
      final GamePlayer p = turnOrder.get(Math.floorMod(start + i, turnOrder.size()));
      if (p.equals(enemy)) {
        return true;
      } else if (p.equals(owner)) {
        return false;
      }
    }
    return false;
  }

  private List<Territory> capitals() {
    return data.getMap().getTerritories().stream()
        .filter(t -> TerritoryAttachment.get(t).map(TerritoryAttachment::isCapital).orElse(false))
        .sorted(Comparator.comparing(Territory::getName))
        .toList();
  }

  private List<Unit> reachingUnits(
      final GamePlayer enemy,
      final Map<Territory, Integer> landDist,
      final Map<Territory, Integer> anyDist) {
    final Predicate<Unit> mobile =
        Matches.unitIsOwnedBy(enemy).and(Matches.unitIsNotInfrastructure());
    final List<Unit> result = new ArrayList<>();
    for (final Map.Entry<Territory, Integer> e : anyDist.entrySet()) {
      final Territory from = e.getKey();
      final int dist = e.getValue();
      for (final Unit u : from.getMatches(mobile)) {
        final int move = u.getUnitAttachment().getMovement(enemy);
        if (Matches.unitIsAir().test(u)) {
          if (dist <= move - 1) {
            result.add(u);
          }
        } else if (Matches.unitIsLand().test(u)) {
          if (from.isWater()) {
            if (dist <= CARGO_RANGE) {
              result.add(u);
            }
          } else {
            final Integer land = landDist.get(from);
            if (land != null && land <= move) {
              result.add(u);
            }
          }
        }
      }
    }
    return result;
  }

  /**
   * Land units, not already in {@code reached}, that {@code enemy}'s own sea transports can load
   * this turn and unload onto {@code capital}: a transport with movement m in zone S can load in
   * any zone Z with seaDist(S, Z) + seaDist(Z, a zone touching the capital) <= m, from land
   * touching Z. Each transport takes the highest-attack candidates that fit its capacity left after
   * its current cargo. Enemy ships on the way and land moves before loading are ignored.
   */
  private List<Unit> amphibiousUnits(
      final GamePlayer enemy, final Territory capital, final Collection<Unit> reached) {
    final List<Territory> landingZones =
        data.getMap().getNeighbors(capital).stream().filter(Territory::isWater).toList();
    final List<Unit> loaded = new ArrayList<>();
    if (landingZones.isEmpty()) {
      return loaded;
    }
    final Map<Territory, Integer> toLanding = seaDistances(landingZones);
    final Set<Unit> taken = new HashSet<>(reached);
    final Predicate<Unit> cargo =
        Matches.unitIsOwnedBy(enemy)
            .and(Matches.unitIsLand())
            .and(Matches.unitIsNotInfrastructure())
            .and(Matches.unitCanBeTransported());
    final List<Territory> zones =
        toLanding.keySet().stream().sorted(Comparator.comparing(Territory::getName)).toList();
    for (final Territory zone : zones) {
      for (final Unit transport :
          zone.getMatches(Matches.unitIsOwnedBy(enemy).and(Matches.unitIsSeaTransport()))) {
        final int move = transport.getUnitAttachment().getMovement(enemy);
        if (toLanding.get(zone) > move) {
          continue;
        }
        int space = transport.getUnitAttachment().getTransportCapacity();
        for (final Unit aboard : transport.getTransporting(zone)) {
          space -= aboard.getUnitAttachment().getTransportCost();
        }
        final Set<Unit> candidates = new HashSet<>();
        for (final Map.Entry<Territory, Integer> e : seaDistances(List.of(zone)).entrySet()) {
          final Integer rest = toLanding.get(e.getKey());
          if (rest == null || e.getValue() + rest > move) {
            continue;
          }
          for (final Territory shore : data.getMap().getNeighbors(e.getKey())) {
            if (!shore.isWater()) {
              candidates.addAll(shore.getMatches(cargo));
            }
          }
        }
        candidates.removeAll(taken);
        final List<Unit> byAttack =
            candidates.stream()
                .sorted(
                    Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getAttack(enemy))
                        .thenComparingInt(u -> u.getUnitAttachment().getTransportCost())
                        .thenComparing(Unit::getId))
                .toList();
        for (final Unit u : byAttack) {
          final int cost = u.getUnitAttachment().getTransportCost();
          if (cost <= space) {
            space -= cost;
            loaded.add(u);
            taken.add(u);
          }
        }
      }
    }
    return loaded;
  }

  /** Breadth-first distances over sea zones only, from the nearest of {@code sources}. */
  private Map<Territory, Integer> seaDistances(final Collection<Territory> sources) {
    final Map<Territory, Integer> dist = new HashMap<>();
    final Deque<Territory> queue = new ArrayDeque<>();
    for (final Territory source : sources) {
      dist.put(source, 0);
      queue.add(source);
    }
    while (!queue.isEmpty()) {
      final Territory t = queue.poll();
      final int d = dist.get(t);
      if (d >= MAX_TRANSPORT_MOVE) {
        continue;
      }
      for (final Territory n : data.getMap().getNeighbors(t)) {
        if (n.isWater() && !dist.containsKey(n)) {
          dist.put(n, d + 1);
          queue.add(n);
        }
      }
    }
    return dist;
  }

  /**
   * Breadth-first distances from {@code target}. With a {@code landMover}, only over passable land,
   * and a route may not pass through a territory holding units at war with it.
   */
  private Map<Territory, Integer> distances(
      final Territory target, @Nullable final GamePlayer landMover) {
    final Map<Territory, Integer> dist = new HashMap<>();
    final Deque<Territory> queue = new ArrayDeque<>();
    dist.put(target, 0);
    queue.add(target);
    final Predicate<Territory> passable =
        landMover != null
            ? Matches.territoryIsLand().and(Matches.territoryIsNotImpassable())
            : t -> true;
    while (!queue.isEmpty()) {
      final Territory t = queue.poll();
      final int d = dist.get(t);
      if (d >= MAX_RANGE
          || (landMover != null
              && !t.equals(target)
              && t.anyUnitsMatch(Matches.enemyUnit(landMover)))) {
        continue;
      }
      for (final Territory n : data.getMap().getNeighbors(t)) {
        if (!dist.containsKey(n) && passable.test(n)) {
          dist.put(n, d + 1);
          queue.add(n);
        }
      }
    }
    return dist;
  }

  private static Set<UUID> ids(final Collection<Unit> units) {
    return units.stream().map(Unit::getId).collect(Collectors.toCollection(HashSet::new));
  }

  private static Map<String, Integer> countByType(final Collection<Unit> units) {
    final Map<String, Integer> counts = new TreeMap<>();
    for (final Unit u : units) {
      counts.merge(u.getType().getName(), 1, Integer::sum);
    }
    return counts;
  }

  private static double round4(final double value) {
    return Math.round(value * 10_000.0) / 10_000.0;
  }

  private static long processCpuNanos() {
    return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
        .getProcessCpuTime();
  }
}
