package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameSequence;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.simulate.ProDummyDelegateBridge;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * {@link ProAiPatch#BID_PLACE_FOR_R1}: spends a bid on units that change round-1 battles, instead
 * of the purchase logic's one-unit-per-territory spread.
 *
 * <p>Simulations on copies of the board give this player's first combat move without a bid, and the
 * combat moves of enemies that move before it. Candidates are bundles of up to three land units for
 * one battle: an enemy territory this player can attack (placed where the units reach it in one
 * move, preferring spots the earlier enemies don't take), or an own territory those enemies attack
 * (placed there). The battle calculator ranks bundles by the change in {@code win% * value + TUV
 * swing} per PU (a target the combat move skipped counts only from the AI's minimum win
 * percentage). The best few are then checked by simulation: an attack bundle scores the mean gain
 * over three combat moves with it on the board (nothing when the move leaves it out), a defence
 * bundle the drop in the earlier enemies' simulated gains against this player. The best checked
 * bundle is taken and the search repeats; leftover PUs go to the stock bid logic.
 */
final class ProBidPlanner {

  /** One bid unit: where it goes, for which battle. */
  record Placement(Territory territory, UnitType unitType, Territory target, boolean attack) {}

  private static final int MAX_BUNDLE = 3;
  private static final double MIN_GAIN = 1.0;
  private static final double PRODUCTION_WEIGHT = 3;
  private static final double CAPITAL_VALUE = 20;
  private static final int ATTACK_TRIES = 3;
  private static final int DEFEND_TRIES = 2;
  private static final int ENEMY_RUNS = 2;
  private static final int ATTACK_RUNS = 3;

  private final AbstractProAi ai;
  private final ProData proData;
  private final ProOddsCalculator calc;
  private final ProCombatMoveAi combatMoveAi;
  private final Map<String, Double> scoreCache = new HashMap<>();

  ProBidPlanner(
      final AbstractProAi ai,
      final ProData proData,
      final ProOddsCalculator calc,
      final ProCombatMoveAi combatMoveAi) {
    this.ai = ai;
    this.proData = proData;
    this.calc = calc;
    this.combatMoveAi = combatMoveAi;
  }

  private record Target(
      Territory territory,
      boolean attack,
      boolean planned,
      List<Unit> attackers,
      List<Unit> defenders,
      Collection<Unit> bombard,
      Map<ProPurchaseOption, Territory> origins) {}

  private record Bundle(Target target, List<ProPurchaseOption> options, int cost, double gain) {}

  /** A simulated combat move: attackers and {@code win% * value + swing} per attacked target. */
  private record SimResult(
      Map<Territory, Set<UUID>> attackers,
      Map<Territory, Double> scores,
      Map<Placement, UUID> ids) {}

  /** Returns the bid units to place; empty when nothing beats the stock bid logic. */
  List<Placement> plan(final GameData data, final GamePlayer player, final int pus) {
    final long start = System.nanoTime();
    final GameStep combatStep = findFirstCombatMoveStep(data, player);
    if (combatStep == null) {
      return List.of();
    }
    final SimResult baseline = simulate(data, player, combatStep, List.of());
    proData.initialize(ai);
    if (baseline == null) {
      return List.of();
    }
    log("Bid R1: baseline attacks " + baseline.scores());
    final Map<UUID, Unit> unitsById = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      t.getUnits().forEach(u -> unitsById.put(u.getId(), u));
    }

    // Attacks on this player planned by enemies that move before its first combat move
    final Map<Territory, List<Unit>> threats = new LinkedHashMap<>();
    final List<GameStep> enemySteps = findEnemyCombatMovesBefore(data, player, combatStep);
    double enemyNow = 0;
    for (int run = 0; run < ENEMY_RUNS; run++) {
      for (final GameStep step : enemySteps) {
        final SimResult enemy = simulate(data, player, step, List.of());
        proData.initialize(ai);
        if (enemy == null) {
          continue;
        }
        enemyNow += enemyScore(enemy, player) / ENEMY_RUNS;
        enemy
            .attackers()
            .forEach(
                (t, ids) -> {
                  if (!t.isWater() && t.isOwnedBy(player) && !threats.containsKey(t)) {
                    final List<Unit> units = new ArrayList<>();
                    ids.stream().map(unitsById::get).filter(Objects::nonNull).forEach(units::add);
                    threats.put(t, units);
                  }
                });
      }
    }
    log("Bid R1: enemy attacks before mine " + threats.keySet());

    final List<Territory> placeLand =
        landPlaceTerritories(data, player, proData.getMyUnitTerritories());
    final List<ProPurchaseOption> landOptions = new ArrayList<>();
    for (final ProPurchaseOption o : proData.getPurchaseOptions().getLandOptions()) {
      if (!o.isConstruction() && !o.isConsumesUnits() && o.getCost() > 0) {
        landOptions.add(o);
      }
    }
    if (placeLand.isEmpty() || landOptions.isEmpty()) {
      return List.of();
    }

    final ProTerritoryManager territoryManager = new ProTerritoryManager(calc, proData);
    territoryManager.populateAttackOptions();

    // Own territories an earlier-moving enemy attacks, and how likely the spot survives
    final Map<Territory, Double> survival = new HashMap<>();
    final List<Target> targets = new ArrayList<>();
    for (final Map.Entry<Territory, List<Unit>> e : threats.entrySet()) {
      final Territory t = e.getKey();
      if (!placeLand.contains(t) || e.getValue().isEmpty()) {
        continue;
      }
      final List<Unit> defenders0 = t.getMatches(Matches.isUnitAllied(player));
      final ProBattleResult r0 =
          calc.calculateBattleResults(proData, t, e.getValue(), defenders0, List.of());
      survival.put(t, 1 - Math.max(0, Math.min(100, r0.getWinPercentage())) / 100);
      final List<Unit> defenders = defenders0;
      final Map<ProPurchaseOption, Territory> origins = new LinkedHashMap<>();
      for (final ProPurchaseOption o : landOptions) {
        if (o.getDefense() > 0) {
          origins.put(o, t);
        }
      }
      targets.add(new Target(t, false, false, e.getValue(), defenders, List.of(), origins));
    }

    // Enemy land territories this player can attack in its first combat move
    final Set<UUID> used = new HashSet<>();
    baseline.attackers().values().forEach(used::addAll);
    final Predicate<Territory> through =
        Matches.territoryIsLand()
            .and(Matches.isTerritoryAllied(player))
            .and(Matches.territoryIsPassableAndNotRestricted(player));
    for (final Map.Entry<Territory, ProTerritory> e :
        territoryManager.getAttackOptions().getTerritoryMap().entrySet()) {
      final Territory target = e.getKey();
      if (target.isWater() || !Matches.isTerritoryEnemy(player).test(target)) {
        continue;
      }
      final boolean planned = baseline.attackers().containsKey(target);
      final List<Unit> attackers = new ArrayList<>();
      if (planned) {
        for (final UUID id : baseline.attackers().get(target)) {
          final Unit u = unitsById.get(id);
          if (u != null) {
            attackers.add(u);
          }
        }
      } else {
        for (final Unit u : e.getValue().getMaxUnits()) {
          if (!used.contains(u.getId())) {
            attackers.add(u);
          }
        }
      }
      final Map<ProPurchaseOption, Territory> origins = new LinkedHashMap<>();
      for (final ProPurchaseOption o : landOptions) {
        if (o.getAttack() <= 0) {
          continue;
        }
        Territory best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (final Territory t : placeLand) {
          final int d = data.getMap().getDistanceIgnoreEndForCondition(t, target, through);
          if (d < 1 || d > o.getMovement()) {
            continue;
          }
          if (best == null
              || survival.getOrDefault(t, 1.0) > survival.getOrDefault(best, 1.0)
              || (survival.getOrDefault(t, 1.0).equals(survival.getOrDefault(best, 1.0))
                  && d < bestDistance)) {
            best = t;
            bestDistance = d;
          }
        }
        if (best != null) {
          origins.put(o, best);
        }
      }
      if (!origins.isEmpty()) {
        targets.add(
            new Target(
                target,
                true,
                planned,
                attackers,
                e.getValue().getMaxEnemyDefenders(player),
                planned ? e.getValue().getBombardTerritoryMap().keySet() : List.of(),
                origins));
      }
    }

    if (Boolean.getBoolean("triplea.ai.bidDebug")) {
      for (final Target t : targets) {
        log(
            String.format(
                "Bid R1:   target %s %s planned=%s attackers=%s defenders=%s origins=%s score0=%.1f",
                t.attack() ? "attack" : "defend",
                t.territory().getName(),
                t.planned(),
                summarize(t.attackers()),
                summarize(t.defenders()),
                t.origins().entrySet().stream()
                    .map(x -> x.getKey().getUnitType().getName() + "@" + x.getValue().getName())
                    .toList(),
                score(t, List.of())));
      }
    }

    // Greedy, one bundle at a time: rank bundles by calculator gain per PU, then check the best
    // attack bundles with a combat-move simulation (the gain there counts, and only if the
    // simulated combat move attacks the target with the bundle's units).
    final Map<Target, List<ProPurchaseOption>> chosen = new LinkedHashMap<>();
    final List<Placement> placements = new ArrayList<>();
    SimResult current = baseline;
    int remaining = pus;
    while (true) {
      final List<Bundle> ranked = new ArrayList<>();
      for (final Target target : targets) {
        final List<ProPurchaseOption> already = chosen.getOrDefault(target, List.of());
        final double now = score(target, already);
        final List<ProPurchaseOption> allowed = new ArrayList<>(target.origins().keySet());
        allowed.sort(Comparator.comparing(o -> o.getUnitType().getName()));
        for (final List<ProPurchaseOption> bundle : bundles(allowed, remaining)) {
          final List<ProPurchaseOption> with = new ArrayList<>(already);
          with.addAll(bundle);
          final double gain = (score(target, with) - now) * survival(target, bundle, survival);
          final int cost = bundle.stream().mapToInt(ProPurchaseOption::getCost).sum();
          if (gain >= MIN_GAIN) {
            ranked.add(new Bundle(target, bundle, cost, gain));
          }
        }
      }
      ranked.sort(Comparator.comparingDouble((Bundle x) -> -x.gain() / x.cost()));
      if (Boolean.getBoolean("triplea.ai.bidDebug")) {
        ranked.stream()
            .limit(8)
            .forEach(
                x ->
                    log(
                        String.format(
                            "Bid R1:   ranked %s %s +%s gain=%.1f cost=%d",
                            x.target().attack() ? "attack" : "defend",
                            x.target().territory().getName(),
                            names(x.options()),
                            x.gain(),
                            x.cost())));
      }
      Bundle best = null;
      SimResult bestSim = null;
      double bestEnemy = enemyNow;
      int attackTries = 0;
      int defendTries = 0;
      for (final Bundle candidate : ranked) {
        if (best != null && candidate.gain() / candidate.cost() <= best.gain() / best.cost()) {
          break;
        }
        if (candidate.target().attack()
            ? attackTries >= ATTACK_TRIES
            : defendTries >= DEFEND_TRIES) {
          continue;
        }
        if (candidate.target().attack()) {
          attackTries++;
        } else {
          defendTries++;
        }
        final List<Placement> trial = new ArrayList<>(placements);
        final List<Placement> added = toPlacements(candidate.target(), candidate.options());
        trial.addAll(added);
        if (!candidate.target().attack()) {
          // Replay the earlier enemies' combat moves with the bundle in place
          double enemyWith = 0;
          for (int run = 0; run < ENEMY_RUNS; run++) {
            for (final GameStep step : enemySteps) {
              final SimResult enemy = simulate(data, player, step, trial);
              proData.initialize(ai);
              enemyWith += enemy == null ? 0 : enemyScore(enemy, player) / ENEMY_RUNS;
            }
          }
          final double gain = enemyNow - enemyWith;
          log(
              String.format(
                  "Bid R1: try defend %s +%s calc gain=%.1f sim gain=%.1f",
                  candidate.target().territory().getName(),
                  names(candidate.options()),
                  candidate.gain(),
                  gain));
          if (gain >= MIN_GAIN
              && (best == null || gain / candidate.cost() > best.gain() / best.cost())) {
            best = new Bundle(candidate.target(), candidate.options(), candidate.cost(), gain);
            bestSim = null;
            bestEnemy = enemyWith;
          }
          continue;
        }
        // The combat move is not deterministic (battle calculator): the gain is the mean over a
        // few simulations, counting nothing when the combat move leaves the bundle out
        final Territory t = candidate.target().territory();
        SimResult sim = null;
        int usedRuns = 0;
        double gainSum = 0;
        for (int run = 0; run < ATTACK_RUNS; run++) {
          final SimResult one = simulate(data, player, combatStep, trial);
          proData.initialize(ai);
          if (one == null) {
            continue;
          }
          final Set<UUID> attackers = one.attackers().getOrDefault(t, Set.of());
          if (added.stream().anyMatch(p -> attackers.contains(one.ids().get(p)))) {
            usedRuns++;
            gainSum += one.scores().getOrDefault(t, 0.0) - current.scores().getOrDefault(t, 0.0);
            sim = one;
          }
        }
        final boolean bidUsed = usedRuns > 0;
        final double gain =
            gainSum / ATTACK_RUNS * survival(candidate.target(), candidate.options(), survival);
        log(
            String.format(
                "Bid R1: try %s +%s calc gain=%.1f sim gain=%.1f used=%d/%d",
                t.getName(),
                names(candidate.options()),
                candidate.gain(),
                gain,
                usedRuns,
                ATTACK_RUNS));
        if (bidUsed && gain >= MIN_GAIN) {
          final Bundle checked =
              new Bundle(candidate.target(), candidate.options(), candidate.cost(), gain);
          if (best == null || checked.gain() / checked.cost() > best.gain() / best.cost()) {
            best = checked;
            bestSim = sim;
          }
        }
      }
      if (best == null) {
        break;
      }
      log(
          String.format(
              "Bid R1: %s %s +%s gain=%.1f cost=%d",
              best.target().attack() ? "attack" : "defend",
              best.target().territory().getName(),
              names(best.options()),
              best.gain(),
              best.cost()));
      chosen.computeIfAbsent(best.target(), k -> new ArrayList<>()).addAll(best.options());
      placements.addAll(toPlacements(best.target(), best.options()));
      if (bestSim != null) {
        current = bestSim;
      } else {
        enemyNow = bestEnemy;
      }
      remaining -= best.cost();
    }
    log(
        String.format(
            "Bid R1: %s placements %s, attacks %s (%.1f s)",
            player.getName(),
            placements.stream()
                .map(p -> p.unitType().getName() + " " + p.territory().getName())
                .toList(),
            current.attackers().keySet(),
            (System.nanoTime() - start) / 1e9));
    return placements;
  }

  private static Map<String, Integer> summarize(final Collection<Unit> units) {
    final Map<String, Integer> out = new java.util.TreeMap<>();
    for (final Unit u : units) {
      out.merge(
          u.getOwner().getName().substring(0, 3) + ":" + u.getType().getName(), 1, Integer::sum);
    }
    return out;
  }

  private static List<String> names(final List<ProPurchaseOption> options) {
    return options.stream().map(o -> o.getUnitType().getName()).toList();
  }

  private static double survival(
      final Target target,
      final List<ProPurchaseOption> bundle,
      final Map<Territory, Double> survival) {
    double s = 1;
    if (target.attack()) {
      for (final ProPurchaseOption o : bundle) {
        s = Math.min(s, survival.getOrDefault(target.origins().get(o), 1.0));
      }
    }
    return s;
  }

  private static List<Placement> toPlacements(
      final Target target, final List<ProPurchaseOption> options) {
    final List<Placement> out = new ArrayList<>();
    for (final ProPurchaseOption o : options) {
      for (int i = 0; i < o.getQuantity(); i++) {
        out.add(
            new Placement(
                target.origins().get(o), o.getUnitType(), target.territory(), target.attack()));
      }
    }
    return out;
  }

  /** Logs to the AI log, and to stdout with {@code -Dtriplea.ai.bidDebug=true} (bid kata). */
  private static void log(final String message) {
    ProLogger.info(message);
    if (Boolean.getBoolean("triplea.ai.bidDebug")) {
      System.out.println("BIDR1 " + message);
    }
  }

  private double score(final Target target, final List<ProPurchaseOption> extra) {
    final List<String> names = new ArrayList<>();
    extra.forEach(o -> names.add(o.getUnitType().getName()));
    names.sort(Comparator.naturalOrder());
    final String key = target.territory().getName() + "|" + target.attack() + "|" + names;
    final Double cached = scoreCache.get(key);
    if (cached != null) {
      return cached;
    }
    final GamePlayer player = proData.getPlayer();
    final List<Unit> added = new ArrayList<>();
    for (final ProPurchaseOption o : extra) {
      added.addAll(o.createTempUnits());
    }
    final double value = value(target.territory());
    double score;
    if (target.attack()) {
      final List<Unit> attackers = new ArrayList<>(target.attackers());
      attackers.addAll(added);
      if (attackers.stream().noneMatch(Matches.unitIsLand())) {
        score = 0;
      } else {
        final ProBattleResult r =
            calc.calculateBattleResults(
                proData, target.territory(), attackers, target.defenders(), target.bombard());
        final double win = Math.max(0, Math.min(100, r.getWinPercentage()));
        if (Boolean.getBoolean("triplea.ai.bidDebug") && !target.planned()) {
          log(String.format("Bid R1:     calc %s win=%.0f swing=%.1f", key, win, r.getTuvSwing()));
        }
        if (!target.planned() && win < proData.getMinWinPercentage()) {
          score = 0;
        } else {
          score = win / 100 * value + clampSwing(r.getTuvSwing());
        }
      }
    } else {
      final List<Unit> defenders = new ArrayList<>(target.defenders());
      defenders.addAll(added);
      final ProBattleResult r =
          calc.calculateBattleResults(
              proData, target.territory(), target.attackers(), defenders, target.bombard());
      final double win = Math.max(0, Math.min(100, r.getWinPercentage()));
      score = -(win / 100 * value + clampSwing(r.getTuvSwing()));
    }
    ProLogger.trace("Bid R1 score " + key + " = " + score + " (player " + player.getName() + ")");
    scoreCache.put(key, score);
    return score;
  }

  /** Sum of {@code win% * value + swing} over an enemy's simulated attacks on the bidder's land. */
  private static double enemyScore(final SimResult enemy, final GamePlayer bidder) {
    double sum = 0;
    for (final Map.Entry<Territory, Double> e : enemy.scores().entrySet()) {
      if (!e.getKey().isWater() && e.getKey().isOwnedBy(bidder)) {
        sum += e.getValue();
      }
    }
    return sum;
  }

  private static double value(final Territory t) {
    final ProCombatMoveAi.ProductionAndIsCapital pc = ProCombatMoveAi.getProductionAndIsCapital(t);
    return PRODUCTION_WEIGHT * pc.production + CAPITAL_VALUE * pc.isCapital;
  }

  /** The calculator's early exits report +-999 swings; keep them from dominating a sum. */
  private static double clampSwing(final double swing) {
    return Math.max(-200, Math.min(200, swing));
  }

  /** Multisets of up to {@link #MAX_BUNDLE} options (sorted list) that fit the budget. */
  private static List<List<ProPurchaseOption>> bundles(
      final List<ProPurchaseOption> options, final int budget) {
    final List<List<ProPurchaseOption>> out = new ArrayList<>();
    addBundles(options, 0, new ArrayList<>(), 0, budget, out);
    return out;
  }

  private static void addBundles(
      final List<ProPurchaseOption> options,
      final int from,
      final List<ProPurchaseOption> current,
      final int cost,
      final int budget,
      final List<List<ProPurchaseOption>> out) {
    if (!current.isEmpty()) {
      out.add(List.copyOf(current));
    }
    if (current.size() >= MAX_BUNDLE) {
      return;
    }
    for (int i = from; i < options.size(); i++) {
      final ProPurchaseOption o = options.get(i);
      if (cost + o.getCost() > budget) {
        continue;
      }
      current.add(o);
      addBundles(options, i, current, cost + o.getCost(), budget, out);
      current.remove(current.size() - 1);
    }
  }

  /**
   * Plays this player's first combat move on a copy of the board with the given bid units added.
   * Returns the attacked territories (of {@code data}) and the ids of the units attacking each.
   */
  private @Nullable SimResult simulate(
      final GameData data,
      final GamePlayer bidder,
      final GameStep combatStep,
      final List<Placement> placements) {
    final Map<Placement, UUID> idsOut = new HashMap<>();
    final GameData copy = ai.copyDataForBid(data);
    if (copy == null) {
      return null;
    }
    final GamePlayer playerCopy =
        copy.getPlayerList().getPlayerId(combatStep.getPlayerId().getName());
    final GamePlayer bidderCopy = copy.getPlayerList().getPlayerId(bidder.getName());
    for (final Placement p : placements) {
      final Territory t = copy.getMap().getTerritoryOrThrow(p.territory().getName());
      final List<Unit> units =
          copy.getUnitTypeList().getUnitTypeOrThrow(p.unitType().getName()).create(1, bidderCopy);
      copy.performChange(ChangeFactory.addUnits(t, units));
      idsOut.put(p, units.get(0).getId());
    }
    copy.getSequence()
        .setRoundAndStep(data.getSequence().getRound(), combatStep.getDisplayName(), playerCopy);
    final IMoveDelegate moveDel = copy.getMoveDelegate();
    moveDel.setDelegateBridgeAndPlayer(new ProDummyDelegateBridge(ai, playerCopy, copy));
    proData.initializeSimulation(ai, copy, playerCopy);
    final ProDecisionListener listener = ai.getDecisionListener();
    ai.setDecisionListener(null);
    final Map<Territory, ProTerritory> moveMap;
    try {
      moveMap = combatMoveAi.doCombatMove(moveDel);
    } finally {
      ai.setDecisionListener(listener);
    }
    final Map<Territory, Set<UUID>> attackers = new LinkedHashMap<>();
    final Map<Territory, Double> scores = new LinkedHashMap<>();
    for (final Map.Entry<Territory, ProTerritory> e : moveMap.entrySet()) {
      final Set<UUID> ids = new HashSet<>();
      e.getValue().getUnits().forEach(u -> ids.add(u.getId()));
      e.getValue().getAmphibAttackMap().values().forEach(l -> l.forEach(u -> ids.add(u.getId())));
      if (ids.isEmpty()) {
        continue;
      }
      final Territory t = data.getMap().getTerritoryOrThrow(e.getKey().getName());
      attackers.put(t, ids);
      final ProBattleResult r = e.getValue().getBattleResult();
      if (r != null) {
        final double win = Math.max(0, Math.min(100, r.getWinPercentage()));
        scores.put(t, win / 100 * value(t) + clampSwing(r.getTuvSwing()));
      }
    }
    return new SimResult(attackers, scores, idsOut);
  }

  private static @Nullable GameStep findFirstCombatMoveStep(
      final GameData data, final GamePlayer player) {
    final GameSequence sequence = data.getSequence();
    for (int i = sequence.getStepIndex() + 1; i < sequence.size(); i++) {
      final GameStep step = sequence.getStep(i);
      if (player.equals(step.getPlayerId())
          && GameStep.isCombatMoveStepName(step.getName())
          && !GameStep.isAirborneCombatMoveStepName(step.getName())) {
        return step;
      }
    }
    return null;
  }

  /** Enemy combat moves between now and this player's first combat move, in turn order. */
  private static List<GameStep> findEnemyCombatMovesBefore(
      final GameData data, final GamePlayer player, final GameStep combatStep) {
    final List<GameStep> result = new ArrayList<>();
    final GameSequence sequence = data.getSequence();
    for (int i = sequence.getStepIndex() + 1; i < sequence.size(); i++) {
      final GameStep step = sequence.getStep(i);
      if (step == combatStep) {
        break;
      }
      final GamePlayer p = step.getPlayerId();
      if (p != null
          && GameStep.isCombatMoveStepName(step.getName())
          && !GameStep.isAirborneCombatMoveStepName(step.getName())
          && player.isAtWar(p)) {
        result.add(step);
      }
    }
    return result;
  }

  /** Land territories a bid unit can be placed in. */
  private static List<Territory> landPlaceTerritories(
      final GameData data, final GamePlayer player, final Collection<Territory> unitTerritories) {
    final Set<Territory> candidates = new HashSet<>(data.getMap().getTerritoriesOwnedBy(player));
    candidates.addAll(unitTerritories);
    final List<Territory> out = new ArrayList<>();
    for (final Territory t : candidates) {
      if (!t.isWater()
          && Matches.territoryIsPassableAndNotRestrictedAndOkByRelationships(
                  player, false, false, false, false, false)
              .test(t)) {
        out.add(t);
      }
    }
    out.sort(Comparator.comparing(Territory::getName));
    return out;
  }
}
