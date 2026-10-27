package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.ai.pro.ProAiPatch;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.BattleRecords;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.IntegerMap;

/**
 * Dan's strategy model ("AI-1"): a per-game strike plan, kill-strike clocks for both sides, pins
 * with TUV accounting, and a per-front "am I winning" sum. Computed at the start of each purchase
 * on the start-of-turn board and kept on {@link ProData}; the DAN_* gates read it.
 */
@UtilityClass
public class ProDanModel {
  public static final int NONE = ProClockUtils.NONE;
  // Rounds the clocks look ahead.
  static final int HORIZON = 10;
  // A shorter enemy clock than mine forces the defence only when it is at most this many rounds.
  public static final int COOKED_HORIZON = 4;
  // With no live plan, strike when my clock is at most this and beats theirs.
  static final int STRIKE_NOW_CLOCK = 3;
  private static final double RATIO = 1.2;
  // Production delivered to a front loses this share per round of travel, up to MAX_DELAY rounds.
  private static final double DELAY_DISCOUNT = 0.6;
  private static final int MAX_DELAY = 3;
  // Plan offsets past the factory's land distance: Dan's R5 / R6 / R8 for Germany -> Moscow (d=4).
  private static final int[] PLAN_OFFSETS = {0, 1, 3};

  /** The strike chosen for a player: target capital and the round the strike lands. */
  public record Plan(@Nullable String target, int strikeRound) {}

  /** One front: my alliance against one enemy power, per round. */
  public record Front(
      double prodMine, double prodTheirs, double tradeNet, double pinNet, double rho) {
    public double winning() {
      return prodMine - prodTheirs + tradeNet + pinNet;
    }
  }

  /** The readings at the start of a player's purchase. */
  public record Turn(
      int round,
      @Nullable String target,
      int strikeRound,
      int myClock,
      int theirClock,
      boolean cooked,
      int decideRound,
      Map<String, Front> fronts) {
    /** Whether a strike is planned that units bought now can still join. */
    public boolean isStriking() {
      return target != null && strikeRound > round && !cooked;
    }
  }

  /** Units held at their objectives by the other side's threats, and the TUV both sides tie up. */
  public record Pins(Set<Unit> held, Map<List<GamePlayer>, double[]> balanceByPair) {}

  public static boolean anyGate(final ProData proData) {
    return proData.hasPatch(ProAiPatch.DAN_PLAN)
        || proData.hasPatch(ProAiPatch.DAN_COOKED)
        || proData.hasPatch(ProAiPatch.DAN_OBJECTIVES)
        || proData.hasPatch(ProAiPatch.DAN_FRONTS)
        || proData.hasPatch(ProAiPatch.DAN_PINS)
        || proData.hasPatch(ProAiPatch.DAN_SUPPLY)
        || proData.hasPatch(ProAiPatch.DAN_FACTORY);
  }

  /** Capitals and victory cities: the only objectives in Dan's model. */
  public static boolean isObjective(final Territory t) {
    return TerritoryAttachment.get(t)
        .map(ta -> ta.isCapital() || ta.getVictoryCity() > 0)
        .orElse(false);
  }

  /** Computes this turn's readings, making the player's plan first if it has none. */
  public static Turn startTurn(
      final ProData proData, final GameData data, final GamePlayer player) {
    final int round = data.getSequence().getRound();
    final List<GamePlayer> enemies = ProUtils.getEnemyPlayers(player);
    final List<GamePlayer> alliance = alliance(player);
    final boolean pinsOn = proData.hasPatch(ProAiPatch.DAN_PINS);
    final boolean supplyOn = proData.hasPatch(ProAiPatch.DAN_SUPPLY);
    proData.getDanHoldCache().clear();
    final Set<Unit> enemyPinned = pinsOn ? pins(data, alliance, enemies).held() : Set.of();
    final Territory capital =
        TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data.getMap())
            .orElse(null);
    final int theirClock =
        capital == null || !capital.isOwnedBy(player)
            ? NONE
            : clock(data, enemies, capital, false, enemyPinned, supplyOn);

    // A strike commits the army that casts my pins, so plans race the enemies' unpinned clock
    final int theirFreeClock =
        enemyPinned.isEmpty() || capital == null || !capital.isOwnedBy(player)
            ? theirClock
            : clock(data, enemies, capital, false, Set.of(), supplyOn);
    final Set<Unit> myPinned = pinsOn ? pins(data, enemies, alliance).held() : Set.of();
    Territory clockTarget = null;
    int myClock = NONE;
    for (final Territory c : ProUtils.getLiveEnemyCapitals(data, player)) {
      final int t = clock(data, alliance, c, true, myPinned, supplyOn);
      if (t < myClock) {
        myClock = t;
        clockTarget = c;
      }
    }

    String target = null;
    int strikeRound = NONE;
    if (proData.hasPatch(ProAiPatch.DAN_PLAN)) {
      Plan plan = proData.getDanPlans().get(player.getName());
      if (plan == null || plan.target() == null || !isEnemyHeld(data, player, plan.target())) {
        plan = makePlan(data, player, round, theirFreeClock);
        proData.getDanPlans().put(player.getName(), plan);
      }
      if (plan.target() != null && plan.strikeRound() > round) {
        target = plan.target();
        strikeRound = plan.strikeRound();
      } else if (clockTarget != null && myClock <= STRIKE_NOW_CLOCK && myClock < theirClock) {
        target = clockTarget.getName();
        strikeRound = round + myClock;
      }
    }
    final int strikeIn = target == null ? myClock : Math.min(myClock, strikeRound - round);
    final boolean cooked =
        theirClock <= 2 || (theirClock < strikeIn && theirClock <= COOKED_HORIZON);
    int decideRound = round + HORIZON;
    if (target != null && !cooked) {
      decideRound = Math.min(decideRound, strikeRound);
    }
    if (theirClock < NONE) {
      decideRound = Math.min(decideRound, round + theirClock);
    }
    final Map<String, Front> fronts =
        proData.hasPatch(ProAiPatch.DAN_FRONTS)
            ? fronts(data, player, alliance, enemies, round, pinsOn, supplyOn)
            : Map.of();
    return new Turn(round, target, strikeRound, myClock, theirClock, cooked, decideRound, fronts);
  }

  /** Shortest clock over both unit mixes. */
  public static int clock(
      final GameData data,
      final Collection<GamePlayer> attackers,
      final Territory capital,
      final boolean attackersBuyNow,
      final Set<Unit> pinned,
      final boolean supply) {
    if (supply) {
      return ProSupplyUtils.timeToKill(data, attackers, capital, attackersBuyNow, HORIZON, pinned);
    }
    return Math.min(
        ProClockUtils.timeToKill(
            data, attackers, capital, ProClockUtils.Mix.ECONOMY, attackersBuyNow, HORIZON, pinned),
        ProClockUtils.timeToKill(
            data, attackers, capital, ProClockUtils.Mix.FAST, attackersBuyNow, HORIZON, pinned));
  }

  public static List<GamePlayer> alliance(final GamePlayer player) {
    final List<GamePlayer> alliance = new ArrayList<>(ProUtils.getAlliedPlayersInTurnOrder(player));
    if (!alliance.contains(player)) {
      alliance.add(player);
    }
    return alliance;
  }

  private static boolean isEnemyHeld(
      final GameData data, final GamePlayer player, final String territory) {
    final Territory t = data.getMap().getTerritoryOrNull(territory);
    return t != null && ProUtils.getLiveEnemyCapitals(data, player).contains(t);
  }

  /**
   * Picks the strike at random among the plans that land before the enemies' clock on my capital:
   * the nearest enemy capital by land from my factories (distance d), struck in round r + d (the
   * starting units plus this round's buy), r + d + 1 or r + d + 3. No plan if none of these wins
   * the race or no enemy capital has a land route.
   */
  static Plan makePlan(
      final GameData data, final GamePlayer player, final int round, final int theirClock) {
    Territory best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (final Territory c : ProUtils.getLiveEnemyCapitals(data, player)) {
      final int d = factoryDistance(data, player, c);
      if (d > 0
          && (d < bestDistance
              || (d == bestDistance
                  && ProUtils.getPlayerProduction(c.getOwner(), data)
                      > ProUtils.getPlayerProduction(best.getOwner(), data)))) {
        best = c;
        bestDistance = d;
      }
    }
    if (best == null) {
      return new Plan(null, NONE);
    }
    final List<Integer> candidates = new ArrayList<>();
    for (final int offset : PLAN_OFFSETS) {
      final int strike = round + bestDistance + offset;
      if (theirClock >= NONE || strike < round + theirClock) {
        candidates.add(strike);
      }
    }
    if (candidates.isEmpty()) {
      return new Plan(null, NONE);
    }
    return new Plan(
        best.getName(), candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
  }

  /** Least land distance from the player's land factories to {@code target}, or -1. */
  public static int factoryDistance(
      final GameData data, final GamePlayer player, final Territory target) {
    int best = -1;
    for (final Territory f : data.getMap().getTerritoriesOwnedBy(player)) {
      if (!ProMatches.territoryHasInfraFactoryAndIsLand().test(f)) {
        continue;
      }
      final int d =
          data.getMap()
              .getDistanceIgnoreEndForCondition(
                  f, target, ProMatches.territoryCanPotentiallyMoveLandUnits(player));
      if (d > 0 && (best < 0 || d < best)) {
        best = d;
      }
    }
    return best;
  }

  /**
   * Income-worthwhile test for a non-objective territory: its income counts only for the rounds it
   * can still turn into force before the decisive round (captured now, spent next turn). Returns
   * the share of the stock 3-round worth that remains, at least 0.1; 1 for objectives or with no
   * reading.
   */
  public static double incomeFactor(final ProData proData, final Territory t) {
    final Turn turn = proData.getDanTurn();
    if (turn == null || !proData.hasPatch(ProAiPatch.DAN_OBJECTIVES) || isObjective(t)) {
      return 1;
    }
    final double rounds = turn.decideRound() - turn.round() - 1;
    final double factor = Math.max(0.1, Math.min(1, rounds / 3));
    if (factor < 1) {
      proData.isPatchOn(ProAiPatch.DAN_OBJECTIVES);
    }
    return factor;
  }

  /**
   * DAN_SUPPLY hold estimate for a non-objective territory: the share of the stock 3 rounds its
   * income counts, by how many turns (0-3) my side's arrival there keeps up with the enemies' once
   * I have it; at least 0.1. 1 for objectives or with the gate off. Cached for the turn.
   */
  public static double holdFactor(final ProData proData, final Territory t) {
    if (proData.getDanTurn() == null
        || !proData.hasPatch(ProAiPatch.DAN_SUPPLY)
        || t.isWater()
        || isObjective(t)) {
      return 1;
    }
    final Double cached = proData.getDanHoldCache().get(t.getName());
    if (cached != null) {
      return cached;
    }
    final GamePlayer player = proData.getPlayer();
    final int held =
        ProSupplyUtils.heldTurns(
            proData.getData(), alliance(player), ProUtils.getEnemyPlayers(player), t, null);
    final double factor = Math.max(0.1, held / 3.0);
    proData.getDanHoldCache().put(t.getName(), factor);
    if (factor < 1) {
      proData.isPatchOn(ProAiPatch.DAN_SUPPLY);
    }
    return factor;
  }

  /**
   * Pins: for every objective of the {@code pinned} side, the {@code pinners}' units that can
   * attack it next turn (strength A) force its owner to keep A / 1.2 there, cheapest defence per
   * strength first. Those units are held. Each pair (pinner, pinned owner) gets {held TUV,
   * committed TUV}; a unit that reaches several objectives splits its TUV among them.
   */
  public static Pins pins(
      final GameData data,
      final Collection<GamePlayer> pinners,
      final Collection<GamePlayer> pinned) {
    return pins(data, pinners, pinned, Set.of());
  }

  /** Like the 3-arg version, as if the {@code absent} units had left. */
  public static Pins pins(
      final GameData data,
      final Collection<GamePlayer> pinners,
      final Collection<GamePlayer> pinned,
      final Set<Unit> absent) {
    final GameMap map = data.getMap();
    final Map<GamePlayer, IntegerMap<UnitType>> costs = new HashMap<>();
    final Map<Territory, Map<Unit, Double>> threatsByTarget = new HashMap<>();
    final Map<Unit, Integer> reach = new HashMap<>();
    for (final Territory p : map.getTerritories()) {
      if (p.isWater() || !pinned.contains(p.getOwner()) || !isObjective(p)) {
        continue;
      }
      final Map<Territory, Integer> landDistance = bfs(map, p, 3, t -> !t.isWater());
      final Map<Territory, Integer> anyDistance = bfs(map, p, 3, t -> true);
      final Map<Unit, Double> threats = new HashMap<>();
      for (final Map.Entry<Territory, Integer> e : anyDistance.entrySet()) {
        final Territory t = e.getKey();
        if (t.equals(p)) {
          continue;
        }
        for (final Unit u : t.getUnits()) {
          if (!pinners.contains(u.getOwner())
              || absent.contains(u)
              || Matches.unitIsInfrastructure().test(u)
              || Matches.unitIsSea().test(u)) {
            continue;
          }
          final UnitAttachment ua = u.getUnitAttachment();
          final int move = Math.max(1, ua.getMovement(u.getOwner()));
          final boolean reaches =
              Matches.unitIsAir().test(u)
                  ? e.getValue() <= move / 2
                  : !t.isWater() && landDistance.getOrDefault(t, 99) <= move;
          if (reaches) {
            threats.put(u, 2.0 * ua.getHitPoints() + ua.getAttack(u.getOwner()));
            reach.merge(u, 1, Integer::sum);
          }
        }
      }
      if (!threats.isEmpty()) {
        threatsByTarget.put(p, threats);
      }
    }
    final Set<Unit> held = new HashSet<>();
    final Map<List<GamePlayer>, double[]> balance = new HashMap<>();
    for (final Map.Entry<Territory, Map<Unit, Double>> e : threatsByTarget.entrySet()) {
      final Territory p = e.getKey();
      final double attack = e.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
      final List<Unit> defenders =
          new ArrayList<>(
              p.getMatches(
                  Matches.isUnitAllied(p.getOwner())
                      .and(Matches.unitIsInfrastructure().negate())
                      .and(Matches.unitIsSea().negate())));
      defenders.sort(
          Comparator.comparingDouble(
              u ->
                  tuv(costs, u)
                      / Math.max(
                          0.1,
                          2.0 * u.getUnitAttachment().getHitPoints()
                              + u.getUnitAttachment().getDefense(u.getOwner()))));
      double need = attack / RATIO;
      double heldTuv = 0;
      for (final Unit u : defenders) {
        if (need <= 0) {
          break;
        }
        held.add(u);
        heldTuv += tuv(costs, u);
        need -=
            2.0 * u.getUnitAttachment().getHitPoints()
                + u.getUnitAttachment().getDefense(u.getOwner());
      }
      for (final Map.Entry<Unit, Double> threat : e.getValue().entrySet()) {
        final Unit u = threat.getKey();
        final double[] pair =
            balance.computeIfAbsent(List.of(u.getOwner(), p.getOwner()), k -> new double[2]);
        pair[0] += heldTuv * threat.getValue() / attack;
        pair[1] += tuv(costs, u) / reach.get(u);
      }
    }
    return new Pins(held, balance);
  }

  private static double tuv(final Map<GamePlayer, IntegerMap<UnitType>> costs, final Unit u) {
    return costs
        .computeIfAbsent(u.getOwner(), o -> new TuvCostsCalculator().getCostsForTuv(o))
        .getInt(u.getType());
  }

  /**
   * Per enemy power E: production each side delivers to the contact line between E and my alliance
   * (each power's income split among the fronts by 0.6^delay, then discounted by 0.6^delay), net
   * TUV traded with E over the last 2 rounds per round, and the pin balance (TUV my pins hold of
   * E's minus what they cost, against the same for E's pins).
   */
  static Map<String, Front> fronts(
      final GameData data,
      final GamePlayer player,
      final List<GamePlayer> alliance,
      final List<GamePlayer> enemies,
      final int round,
      final boolean pinsOn,
      final boolean supplyOn) {
    final GameMap map = data.getMap();
    final List<GamePlayer> everyone = new ArrayList<>(alliance);
    everyone.addAll(enemies);
    final Map<GamePlayer, Map<GamePlayer, Double>> weights = new HashMap<>();
    for (final GamePlayer x : everyone) {
      weights.put(x, new HashMap<>());
    }
    for (final GamePlayer e : enemies) {
      final List<Territory> zone = new ArrayList<>();
      for (final Territory t : map.getTerritories()) {
        if (t.isWater()) {
          continue;
        }
        final Predicate<Territory> other =
            e.equals(t.getOwner())
                ? n -> !n.isWater() && alliance.contains(n.getOwner())
                : n -> !n.isWater() && e.equals(n.getOwner());
        if ((e.equals(t.getOwner()) || alliance.contains(t.getOwner()))
            && map.getNeighbors(t).stream().anyMatch(other)) {
          zone.add(t);
        }
      }
      if (zone.isEmpty()) {
        zone.addAll(map.getTerritoriesOwnedBy(e).stream().filter(t -> !t.isWater()).toList());
      }
      final Map<Territory, Integer> land = bfs(map, zone, 99, t -> !t.isWater());
      final Map<Territory, Integer> any = bfs(map, zone, 99, t -> true);
      for (final GamePlayer x : everyone) {
        final int delay = delay(data, x, land, any, supplyOn);
        weights.get(x).put(e, delay > MAX_DELAY ? 0 : Math.pow(DELAY_DISCOUNT, delay));
      }
    }
    final Pins mine = pinsOn ? pins(data, alliance, enemies) : null;
    final Pins theirs = pinsOn ? pins(data, enemies, alliance) : null;
    final Map<String, Front> fronts = new HashMap<>();
    for (final GamePlayer e : enemies) {
      double prodMine = 0;
      for (final GamePlayer x : alliance) {
        prodMine += delivered(data, x, e, weights.get(x));
      }
      double prodTheirs = 0;
      for (final GamePlayer x : enemies) {
        prodTheirs += delivered(data, x, e, weights.get(x));
      }
      double eLost = 0;
      double myLost = 0;
      final Map<Integer, BattleRecords> records = data.getBattleRecordsList().getBattleRecordsMap();
      final Predicate<GamePlayer> isE = e::equals;
      final Predicate<GamePlayer> isMine = alliance::contains;
      for (int r = round - 1; r <= round; r++) {
        final BattleRecords brs = records.get(r);
        if (brs == null) {
          continue;
        }
        eLost += BattleRecords.getLostTuvBetween(brs, isMine, isE, false);
        eLost += BattleRecords.getLostTuvBetween(brs, isE, isMine, true);
        myLost += BattleRecords.getLostTuvBetween(brs, isMine, isE, true);
        myLost += BattleRecords.getLostTuvBetween(brs, isE, isMine, false);
      }
      final double tradeNet = (eLost - myLost) / 2;
      double pinNet = 0;
      if (pinsOn) {
        for (final GamePlayer p : alliance) {
          final double[] m = mine.balanceByPair().get(List.of(p, e));
          if (m != null) {
            pinNet += m[0] - m[1];
          }
          final double[] t = theirs.balanceByPair().get(List.of(e, p));
          if (t != null) {
            pinNet -= t[0] - t[1];
          }
        }
      }
      final double rho =
          (prodMine + Math.max(0, tradeNet)) / Math.max(1, prodTheirs + Math.max(0, -tradeNet));
      fronts.put(e.getName(), new Front(prodMine, prodTheirs, tradeNet, pinNet, rho));
    }
    return fronts;
  }

  private static double delivered(
      final GameData data,
      final GamePlayer p,
      final GamePlayer e,
      final Map<GamePlayer, Double> weights) {
    final double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
    final double w = weights.getOrDefault(e, 0.0);
    return total <= 0 ? 0 : ProUtils.getPlayerProduction(p, data) * w * w / total;
  }

  /**
   * Rounds before a unit placed at the player's nearest land factory reaches the front: land steps
   * minus one, or for a factory with no land route one turn to load plus sailing at 2 zones a turn.
   */
  private static int delay(
      final GameData data,
      final GamePlayer p,
      final Map<Territory, Integer> landFromFront,
      final Map<Territory, Integer> anyFromFront,
      final boolean supplyOn) {
    int best = NONE;
    for (final Territory f : data.getMap().getTerritoriesOwnedBy(p)) {
      if (!ProMatches.territoryHasInfraFactoryAndIsLand().test(f)) {
        continue;
      }
      final Integer land = landFromFront.get(f);
      if (land != null) {
        best = Math.min(best, Math.max(0, land - 1));
      } else if (supplyOn) {
        // DAN_SUPPLY: only a transport shuttle the owner can run, with naval dominance
        final ProSupplyUtils.Shuttle shuttle = ProSupplyUtils.shuttle(data, p, f, landFromFront);
        if (shuttle != null) {
          best = Math.min(best, Math.max(0, shuttle.sailTurns() + shuttle.march() - 1));
        }
      } else {
        final Integer any = anyFromFront.get(f);
        if (any != null) {
          best = Math.min(best, 1 + (any + 1) / 2);
        }
      }
    }
    return best;
  }

  private static Map<Territory, Integer> bfs(
      final GameMap map, final Territory from, final int max, final Predicate<Territory> passable) {
    return bfs(map, List.of(from), max, passable);
  }

  private static Map<Territory, Integer> bfs(
      final GameMap map,
      final Collection<Territory> from,
      final int max,
      final Predicate<Territory> passable) {
    final Map<Territory, Integer> d = new HashMap<>();
    final ArrayDeque<Territory> queue = new ArrayDeque<>();
    for (final Territory t : from) {
      d.put(t, 0);
      queue.add(t);
    }
    final Predicate<Territory> open = passable.and(Matches.territoryIsImpassable().negate());
    while (!queue.isEmpty()) {
      final Territory x = queue.poll();
      if (d.get(x) >= max) {
        continue;
      }
      for (final Territory y : map.getNeighbors(x)) {
        if (!d.containsKey(y) && open.test(y)) {
          d.put(y, d.get(x) + 1);
          queue.add(y);
        }
      }
    }
    return d;
  }
}
