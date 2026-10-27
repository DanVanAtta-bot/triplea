package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
import games.strategy.triplea.ai.pro.data.ProPurchaseOptionMap;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;

/**
 * Supply lines (Dan's model, A6/A7): how much strength one side can have at a territory by each of
 * its next turns. Turn 1 is units already on the board; later turns add this and later purchases,
 * each from the factory that feeds the territory, capped by the factory's production and, for a
 * factory with no land route, by the throughput of the side's transports shuttling from it (only
 * with naval dominance on the route). Past the slowest feeding unit's travel time the curve grows
 * at a steady rate: the feeding factories' production. Pinned units stay out.
 */
@UtilityClass
public class ProSupplyUtils {
  public static final int NONE = ProClockUtils.NONE;
  private static final double RATIO = 1.2;
  private static final int MAX_SEA = 12;

  /**
   * Cumulative strength by turn (index 0 = now), the steady per-turn rate and when it's reached.
   */
  public record Arrival(double[] cumulative, double steadyRate, int horizon) {
    public double at(final int t) {
      return cumulative[Math.max(0, Math.min(t, cumulative.length - 1))];
    }
  }

  /** A factory feeding the target: turns for its slowest unit to arrive, units per turn. */
  record Feed(int time, double unitsPerTurn) {}

  /**
   * Strength {@code side} can have at {@code x} by each of its next {@code maxRounds} turns.
   *
   * @param attacking attack strength (2 x HP + attack) for a side moving in, else defence
   * @param buyNow the side's bank buys units now (its own purchase phase)
   * @param excluded units that stay where they are (pinned)
   * @param extraFactory a land territory treated as having a factory (a candidate), or null
   */
  public static Arrival arrival(
      final GameData data,
      final Collection<GamePlayer> side,
      final Territory x,
      final boolean attacking,
      final boolean buyNow,
      final int maxRounds,
      final Set<Unit> excluded,
      @Nullable final Territory extraFactory) {
    final GameMap map = data.getMap();
    final Map<Territory, Integer> land = landDistances(map, x);
    final Map<Territory, Integer> any =
        ProClockUtils.bfs(map, x, Matches.territoryIsImpassable().negate());
    final double[] add = new double[maxRounds + 1];

    for (final Territory t : map.getTerritories()) {
      for (final Unit u : t.getUnits()) {
        if (!side.contains(u.getOwner())
            || excluded.contains(u)
            || Matches.unitIsInfrastructure().test(u)
            || Matches.unitIsSea().test(u)) {
          continue;
        }
        final UnitAttachment ua = u.getUnitAttachment();
        final int move = Math.max(1, ua.getMovement(u.getOwner()));
        final int arrival;
        if (Matches.unitIsAir().test(u)) {
          final Integer d = any.get(t);
          if (d == null) {
            continue;
          }
          arrival =
              attacking
                  ? ProClockUtils.airAttackArrival(d, move)
                  : (d == 0 ? 0 : ProClockUtils.ceilDiv(d, move));
        } else {
          final Integer d = t.isWater() ? null : land.get(t);
          if (d == null || (attacking && d == 0)) {
            continue;
          }
          arrival = d == 0 ? 0 : Math.max(1, ProClockUtils.ceilDiv(d, move));
        }
        if (arrival <= maxRounds) {
          add[arrival] +=
              2.0 * ua.getHitPoints()
                  + (attacking ? ua.getAttack(u.getOwner()) : ua.getDefense(u.getOwner()));
        }
      }
    }

    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    double steady = 0;
    int horizon = 0;
    for (final GamePlayer p : side) {
      final ProPurchaseOption unit = bestUnit(p, data, attacking);
      if (unit == null) {
        continue;
      }
      final double perPu = strength(unit, attacking) / unit.getCost();
      final double puPerUnit = (double) unit.getCost() / unit.getQuantity();
      final List<Feed> feeds = new ArrayList<>();
      for (final Territory f : map.getTerritories()) {
        final boolean factory =
            f.isOwnedBy(p) && ProMatches.territoryHasInfraFactoryAndIsLand().test(f);
        if (factory || f.equals(extraFactory) && f.isOwnedBy(p)) {
          final Feed feed = feed(data, p, f, x, land, unit.getMovement(), !attacking);
          if (feed != null) {
            feeds.add(feed);
          }
        }
      }
      feeds.sort(Comparator.comparingInt(Feed::time));
      final double income = ProUtils.getPlayerProduction(p, data);
      final double bank = p.getResources().getQuantity(pus);
      double spent = 0;
      for (final Feed feed : feeds) {
        final double pusHere = Math.min(income - spent, feed.unitsPerTurn() * puPerUnit);
        if (pusHere <= 0) {
          break;
        }
        spent += pusHere;
        steady += pusHere * perPu;
        horizon = Math.max(horizon, feed.time());
      }
      for (int i = buyNow ? 0 : 1; i <= maxRounds; i++) {
        double budget = i == 0 ? bank : income;
        for (final Feed feed : feeds) {
          final double pusHere = Math.min(budget, feed.unitsPerTurn() * puPerUnit);
          if (pusHere <= 0) {
            break;
          }
          budget -= pusHere;
          if (i + feed.time() <= maxRounds) {
            add[i + feed.time()] += pusHere * perPu;
          }
        }
      }
    }
    final double[] cumulative = new double[maxRounds + 1];
    double sum = 0;
    for (int t = 0; t <= maxRounds; t++) {
      sum += add[t];
      cumulative[t] = sum;
    }
    return new Arrival(cumulative, steady, horizon);
  }

  /**
   * Kill-strike clock on supply lines: the first turn the attackers' arrival at the capital is at
   * least 1.2 x the defenders'. The attackers leave their slow pinned units behind; pinned fast
   * movers (armour, air) may leave their pins for a kill strike (Dan's "unpin surge").
   */
  public static int timeToKill(
      final GameData data,
      final Collection<GamePlayer> attackers,
      final Territory capital,
      final boolean attackersBuyNow,
      final int maxRounds,
      final Set<Unit> pinned) {
    final Set<Unit> slowPinned = new java.util.HashSet<>();
    for (final Unit u : pinned) {
      if (!Matches.unitIsAir().test(u) && u.getUnitAttachment().getMovement(u.getOwner()) <= 1) {
        slowPinned.add(u);
      }
    }
    final Arrival attack =
        arrival(data, attackers, capital, true, attackersBuyNow, maxRounds, slowPinned, null);
    final List<GamePlayer> defenders = new ArrayList<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (data.getRelationshipTracker().isAllied(p, capital.getOwner())) {
        defenders.add(p);
      }
    }
    final Arrival defence =
        arrival(data, defenders, capital, false, false, maxRounds, Set.of(), null);
    for (int t = 1; t <= maxRounds; t++) {
      if (attack.at(t) >= RATIO * defence.at(t)) {
        return t;
      }
    }
    return NONE;
  }

  /**
   * Turns (0-3) {@code side} can hold {@code x} once it has it: the first turn the enemies' arrival
   * beats 1.2 x the side's own ends the hold.
   */
  public static int heldTurns(
      final GameData data,
      final Collection<GamePlayer> side,
      final Collection<GamePlayer> enemies,
      final Territory x,
      @Nullable final Territory extraFactory) {
    final Arrival mine = arrival(data, side, x, false, false, 3, Set.of(), extraFactory);
    final Arrival theirs = arrival(data, enemies, x, true, false, 3, Set.of(), null);
    int held = 0;
    for (int t = 1; t <= 3; t++) {
      if (theirs.at(t) > RATIO * mine.at(t)) {
        break;
      }
      held++;
    }
    return held;
  }

  /**
   * How factory {@code f} feeds {@code x}: by land, turns = distance / speed; with no land route,
   * by a transport shuttle to the landing nearest x, if the owner has naval dominance on the route
   * and transports there. Null if it can't feed x.
   */
  @Nullable
  static Feed feed(
      final GameData data,
      final GamePlayer p,
      final Territory f,
      final Territory x,
      final Map<Territory, Integer> land,
      final int speed,
      final boolean defending) {
    final int production = TerritoryAttachment.getProduction(f);
    if (production <= 0) {
      return null;
    }
    final Integer d = land.get(f);
    if (d != null) {
      final int time =
          d == 0 ? (defending ? 0 : 1) : Math.max(1, ProClockUtils.ceilDiv(d, Math.max(1, speed)));
      return new Feed(time, production);
    }
    final Shuttle s = shuttle(data, p, f, land);
    if (s == null) {
      return null;
    }
    final double units = Math.min(production, s.unitsPerTurn());
    return units <= 0 ? null : new Feed(s.sailTurns() + s.march(), units);
  }

  /** A transport shuttle: turns to sail, then land steps to march, and units it lands per turn. */
  record Shuttle(int sailTurns, int march, double unitsPerTurn) {}

  @Nullable
  static Shuttle shuttle(
      final GameData data,
      final GamePlayer p,
      final Territory f,
      final Map<Territory, Integer> land) {
    final GameMap map = data.getMap();
    final List<Territory> ports = new ArrayList<>(map.getNeighbors(f, Matches.territoryIsWater()));
    if (ports.isEmpty()) {
      return null;
    }
    final Map<Territory, Integer> sea = new java.util.HashMap<>();
    final java.util.ArrayDeque<Territory> queue = new java.util.ArrayDeque<>();
    for (final Territory z : ports) {
      sea.put(z, 0);
      queue.add(z);
    }
    final Predicate<Territory> water =
        Matches.territoryIsWater().and(Matches.territoryIsImpassable().negate());
    while (!queue.isEmpty()) {
      final Territory z = queue.poll();
      if (sea.get(z) >= MAX_SEA) {
        continue;
      }
      for (final Territory n : map.getNeighbors(z, water)) {
        if (!sea.containsKey(n)) {
          sea.put(n, sea.get(z) + 1);
          queue.add(n);
        }
      }
    }
    int bestTime = NONE;
    int bestSail = 0;
    int bestMarch = 0;
    int bestSea = 0;
    for (final Map.Entry<Territory, Integer> e : sea.entrySet()) {
      for (final Territory l : map.getNeighbors(e.getKey(), Matches.territoryIsLand())) {
        final Integer march = land.get(l);
        if (march == null) {
          continue;
        }
        final int sail = Math.max(1, ProClockUtils.ceilDiv(e.getValue(), 2));
        if (sail + march < bestTime) {
          bestTime = sail + march;
          bestSail = sail;
          bestMarch = march;
          bestSea = e.getValue();
        }
      }
    }
    if (bestTime == NONE) {
      return null;
    }
    // Naval dominance on the route: my alliance's fleet there against the enemies' fleet and air
    double mine = 0;
    double theirs = 0;
    int capacity = 0;
    final Set<Territory> nearAir = new java.util.HashSet<>();
    for (final Map.Entry<Territory, Integer> e : sea.entrySet()) {
      if (e.getValue() > bestSea + 2) {
        continue;
      }
      final Territory z = e.getKey();
      final boolean onRoute = e.getValue() <= bestSea;
      nearAir.add(z);
      nearAir.addAll(map.getNeighbors(z));
      for (final Unit u : z.getUnits()) {
        final UnitAttachment ua = u.getUnitAttachment();
        if (u.isOwnedBy(p) && Matches.unitIsSeaTransport().test(u)) {
          capacity += Math.max(0, ua.getTransportCapacity());
        }
        if (!onRoute || !Matches.unitIsSea().test(u) || Matches.unitIsSeaTransport().test(u)) {
          continue;
        }
        if (data.getRelationshipTracker().isAllied(p, u.getOwner())) {
          mine += 2.0 * ua.getHitPoints() + ua.getDefense(u.getOwner());
        } else if (data.getRelationshipTracker().isAtWar(p, u.getOwner())) {
          theirs += 2.0 * ua.getHitPoints() + ua.getAttack(u.getOwner());
        }
      }
    }
    for (final Territory t : nearAir) {
      for (final Unit u : t.getUnits()) {
        if (Matches.unitIsAir().test(u) && data.getRelationshipTracker().isAtWar(p, u.getOwner())) {
          theirs +=
              2.0 * u.getUnitAttachment().getHitPoints()
                  + u.getUnitAttachment().getAttack(u.getOwner());
        }
      }
    }
    if (theirs > mine || capacity <= 0) {
      return null;
    }
    // Two infantry-sized units per Revised transport (capacity 5, infantry cost 2)
    final double unitsPerTrip = Math.max(1, capacity / 2);
    final int roundTrip = 2 * bestSail;
    return new Shuttle(bestSail, bestMarch, unitsPerTrip / roundTrip);
  }

  @Nullable
  private static ProPurchaseOption bestUnit(
      final GamePlayer p, final GameData data, final boolean attacking) {
    ProPurchaseOption best = null;
    for (final ProPurchaseOption o : new ProPurchaseOptionMap(p, data).getLandOptions()) {
      if (o.getCost() > 0
          && (attacking ? o.getAttack() : o.getDefense()) > 0
          && (best == null
              || strength(o, attacking) / o.getCost() > strength(best, attacking) / best.getCost()
              || (strength(o, attacking) / o.getCost() == strength(best, attacking) / best.getCost()
                  && o.getCost() < best.getCost()))) {
        best = o;
      }
    }
    return best;
  }

  private static double strength(final ProPurchaseOption o, final boolean attacking) {
    return 2.0 * o.getHitPoints() + (attacking ? o.getAttack() : o.getDefense());
  }

  static Map<Territory, Integer> landDistances(final GameMap map, final Territory x) {
    return ProClockUtils.bfs(
        map,
        x,
        t ->
            !t.isWater()
                && Matches.territoryIsImpassable().negate().test(t)
                && !ProUtils.isNeutralLand(t));
  }
}
