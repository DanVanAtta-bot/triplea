package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
import games.strategy.triplea.ai.pro.data.ProPurchaseOptionMap;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import lombok.experimental.UtilityClass;

/**
 * Kill test: can the player take an enemy capital on its next turn whatever the enemy does in
 * between? Attackers are the player's units that reach the capital next turn plus what this turn's
 * PUs can place at factories in reach. Defenders are an upper bound: every unit of the capital's
 * side that can reach it, plus the owner's maximum infantry-style buy placed there.
 */
@UtilityClass
public class ProKillUtils {
  /** Strength-heuristic cutoff below which the battle calculator isn't run. */
  private static final double STRENGTH_GATE = 60;

  /**
   * A capital that falls next turn.
   *
   * @param factoryDistance land distance to the capital of each factory whose new units take part
   */
  public record KillTarget(
      Territory capital,
      Map<Territory, Integer> factoryDistance,
      boolean needsPurchase,
      double winPercentage) {}

  /** Returns the best capital the player can take next turn, if any. */
  public static Optional<KillTarget> findNextTurnKill(
      final ProData proData,
      final ProOddsCalculator calc,
      final Map<Territory, ProPurchaseTerritory> purchaseTerritories) {
    final GamePlayer player = proData.getPlayer();
    final GameState data = proData.getData();
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final int budget = player.getResources().getQuantity(pus);
    KillTarget best = null;
    for (final Territory capital : ProUtils.getLiveEnemyCapitals(data, player)) {
      if (capital.isWater()) {
        continue;
      }
      final Set<Unit> attackers = reachingUnits(data, player, capital, true);
      final Map<Territory, Integer> factoryDistance = new HashMap<>();
      final List<Unit> bought =
          bestAttackPurchase(
              proData, player, capital, purchaseTerritories, budget, factoryDistance);
      final Set<Unit> defenders = maxDefenders(proData, capital);
      final List<Unit> withPurchase = new ArrayList<>(attackers);
      withPurchase.addAll(bought);
      if (withPurchase.stream().noneMatch(Matches.unitIsLand())) {
        continue;
      }
      final double diffWithout =
          ProBattleUtils.estimateStrengthDifference(capital, attackers, defenders);
      final double diffWith =
          ProBattleUtils.estimateStrengthDifference(capital, withPurchase, defenders);
      ProLogger.debug(
          String.format(
              "Kill test %s: attackers=%d bought=%d defenders=%d diff %.0f -> %.0f",
              capital.getName(),
              attackers.size(),
              bought.size(),
              defenders.size(),
              diffWithout,
              diffWith));
      if (diffWith < STRENGTH_GATE) {
        continue;
      }
      final ProBattleResult result =
          calc.calculateBattleResults(proData, capital, withPurchase, defenders, List.of());
      ProLogger.info(
          String.format(
              "Kill test %s: win=%.1f landLeft=%s attackers=%s bought=%s defenders=%s",
              capital.getName(),
              result.getWinPercentage(),
              result.isHasLandUnitRemaining(),
              ProUtils.summarizeUnits(attackers),
              ProUtils.summarizeUnits(bought),
              ProUtils.summarizeUnits(defenders)));
      if (result.getWinPercentage() < proData.getWinPercentage()
          || !result.isHasLandUnitRemaining()) {
        continue;
      }
      final boolean needsPurchase = diffWithout < STRENGTH_GATE;
      if (best == null || result.getWinPercentage() > best.winPercentage()) {
        best = new KillTarget(capital, factoryDistance, needsPurchase, result.getWinPercentage());
      }
    }
    return Optional.ofNullable(best);
  }

  /**
   * Units of {@code side} (owned by the player if {@code ownOnly}, else by anyone allied to it)
   * that can be in {@code target} on their next move: land units over allied land within their
   * movement, air within half its movement when attacking (it must fly back) or its full movement
   * when defending (it lands there).
   */
  private static Set<Unit> reachingUnits(
      final GameState data, final GamePlayer side, final Territory target, final boolean attack) {
    final GameMap map = data.getMap();
    final Set<Unit> result = new LinkedHashSet<>();
    final Predicate<Unit> mine = attack ? Matches.unitIsOwnedBy(side) : Matches.alliedUnit(side);
    for (final Territory t : map.getTerritories()) {
      final List<Unit> units = t.getMatches(mine.and(Matches.unitIsNotInfrastructure()));
      if (units.isEmpty()) {
        continue;
      }
      if (t.equals(target)) {
        if (!attack) {
          result.addAll(units);
        }
        continue;
      }
      int landDistance = -2;
      int airDistance = -2;
      for (final Unit u : units) {
        final int movement = u.getUnitAttachment().getMovement(u.getOwner());
        if (Matches.unitIsAir().test(u)) {
          if (airDistance == -2) {
            airDistance = map.getDistance(t, target);
          }
          final int range = attack ? movement / 2 : movement;
          if (airDistance >= 0 && airDistance <= range) {
            result.add(u);
          }
        } else if (Matches.unitIsLand().test(u) && !t.isWater()) {
          if (landDistance == -2) {
            landDistance =
                map.getDistanceIgnoreEndForCondition(
                    t, target, ProMatches.territoryCanMoveLandUnitsAndIsAllied(side));
          }
          if (landDistance >= 0 && landDistance <= movement) {
            result.add(u);
          }
        }
      }
    }
    return result;
  }

  /**
   * Spends {@code budget} on the land unit with the best attack strength per PU among those fast
   * enough, at the player's factories closest to {@code capital} first.
   */
  private static List<Unit> bestAttackPurchase(
      final ProData proData,
      final GamePlayer player,
      final Territory capital,
      final Map<Territory, ProPurchaseTerritory> purchaseTerritories,
      final int budget,
      final Map<Territory, Integer> factoryDistance) {
    final GameMap map = proData.getData().getMap();
    final List<ProPurchaseOption> options = proData.getPurchaseOptions().getLandOptions();
    for (final Territory f : purchaseTerritories.keySet()) {
      if (f.isWater()) {
        continue;
      }
      final int d =
          map.getDistanceIgnoreEndForCondition(
              f, capital, ProMatches.territoryCanMoveLandUnitsAndIsAllied(player));
      if (d >= 1 && options.stream().anyMatch(o -> o.getMovement() >= d && o.getAttack() > 0)) {
        factoryDistance.put(f, d);
      }
    }
    final List<Unit> bought = new ArrayList<>();
    int left = budget;
    final List<Territory> byDistance = new ArrayList<>(factoryDistance.keySet());
    byDistance.sort(Comparator.comparingInt(factoryDistance::get));
    for (final Territory f : byDistance) {
      final int d = factoryDistance.get(f);
      final Optional<ProPurchaseOption> best =
          options.stream()
              .filter(o -> o.getMovement() >= d && o.getAttack() > 0 && o.getCost() > 0)
              .max(Comparator.comparingDouble(o -> strengthPerPu(o, true)));
      if (best.isEmpty()) {
        continue;
      }
      int capacity = purchaseTerritories.get(f).getUnitProduction();
      while (capacity >= best.get().getQuantity() && left >= best.get().getCost()) {
        bought.addAll(best.get().createTempUnits());
        capacity -= best.get().getQuantity();
        left -= best.get().getCost();
      }
    }
    return bought;
  }

  /** Units in the capital, units of its side that can reach it, plus the owner's max buy. */
  private static Set<Unit> maxDefenders(final ProData proData, final Territory capital) {
    final GameState data = proData.getData();
    final GamePlayer owner = capital.getOwner();
    final Set<Unit> defenders = reachingUnits(data, owner, capital, false);
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    int left = owner.getResources().getQuantity(pus);
    int capacity = TerritoryAttachment.getProduction(capital);
    final Optional<ProPurchaseOption> best =
        new ProPurchaseOptionMap(owner, proData.getData())
            .getLandOptions().stream()
                .filter(o -> o.getCost() > 0 && o.getDefense() > 0)
                .max(Comparator.comparingDouble(o -> strengthPerPu(o, false)));
    if (best.isPresent()) {
      while (capacity >= best.get().getQuantity() && left >= best.get().getCost()) {
        defenders.addAll(best.get().createTempUnits());
        capacity -= best.get().getQuantity();
        left -= best.get().getCost();
      }
    }
    return defenders;
  }

  /** {@link ProBattleUtils#estimateStrength}'s 2 x hit points + power, per PU. */
  private static double strengthPerPu(final ProPurchaseOption o, final boolean attack) {
    final double power = attack ? o.getAttack() : o.getDefense();
    return (2.0 * o.getHitPoints() + power) / o.getCost();
  }
}
