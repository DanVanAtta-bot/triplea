package games.strategy.triplea.ai.harness;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.engine.framework.ServerGame;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProDecisionListener;
import games.strategy.triplea.ai.pro.ProDecisionListener.AttackDecision;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.AbstractEndTurnDelegate;
import games.strategy.triplea.delegate.AbstractMoveDelegate;
import games.strategy.triplea.delegate.AbstractPlaceDelegate;
import games.strategy.triplea.delegate.BidPurchaseDelegate;
import games.strategy.triplea.delegate.EndRoundDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.PurchaseDelegate;
import games.strategy.triplea.delegate.TerritoryEffectHelper;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.odds.calculator.AggregateResults;
import games.strategy.triplea.odds.calculator.ConcurrentBattleCalculator;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Measures one headless game by diffing unit snapshots around every {@link
 * ServerGame#runNextStep()}. Writes JSON lines to {@code records.jsonl}: one {@code round} record
 * per round per side, one {@code battle} record per battle, and one {@code ai_attack} record per
 * target ProAI considered. Keeps Purchase-step saves of rounds that trip a flag rule.
 *
 * <p>Call {@link #beforeStep()} and {@link #afterStep()} around each {@code runNextStep()}, then
 * {@link #finish(Map)}. Not thread-safe; the game must be idle between steps (it is, after {@code
 * runNextStep()} returns).
 */
final class GameRecorder {

  record Options(int oddsRuns, int flagCombat, double flagOdds) {}

  private record UnitRec(String type, GamePlayer owner, String where, int cost, boolean infra) {}

  private record Snapshot(
      Map<UUID, UnitRec> units,
      Map<String, Integer> tuvBySide,
      Map<String, Integer> mobileBySide) {}

  /** Per side, per round accumulators. Losses are attributed to the side that owned the unit. */
  private static final class SideAcc {
    int production;
    int otherGain;
    int combatLoss;
    final Map<String, Integer> combatLossByTurn = new TreeMap<>();
    final Map<String, Integer> otherLoss = new TreeMap<>();
    int capturedIn;
    int capturedOut;
    int pusSpent;
    int income;
    int battlesAttacked;
    final List<String> flags = new ArrayList<>();

    int otherLossTotal() {
      return otherLoss.values().stream().mapToInt(Integer::intValue).sum();
    }

    int net() {
      return production + otherGain - combatLoss - otherLossTotal() + capturedIn - capturedOut;
    }
  }

  private static final class PendingBattle {
    final IBattle battle;
    final Territory territory;
    final GamePlayer attacker;
    String ownerBefore;
    final List<UUID> attackers;
    final List<UUID> defenders;
    final boolean risksCapitalOrVc;
    final Map<String, Object> record;
    @Nullable Double harnessWin;
    @Nullable Double expectedSwing;

    PendingBattle(
        final IBattle battle,
        final List<UUID> attackers,
        final List<UUID> defenders,
        final boolean risksCapitalOrVc,
        final Map<String, Object> record) {
      this.battle = battle;
      this.territory = battle.getTerritory();
      this.attacker = battle.getAttacker();
      this.ownerBefore = territory.getOwner().getName();
      this.attackers = attackers;
      this.defenders = defenders;
      this.risksCapitalOrVc = risksCapitalOrVc;
      this.record = record;
    }
  }

  private static final Gson GSON =
      new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

  private final ServerGame game;
  private final GameData data;
  private final String gameId;
  private final Path dir;
  private final Path ringDir;
  private final Path savesDir;
  private final Options options;
  private final DecisionLog decisionLog;
  private final Map<String, String> variantBySide;
  private final Map<GamePlayer, String> sideOf = new LinkedHashMap<>();
  private final Map<String, List<GamePlayer>> playersBySide = new LinkedHashMap<>();
  private final TuvCostsCalculator costs = new TuvCostsCalculator();
  private final Resource pus;
  private final BufferedWriter out;
  private final Map<String, Object> config;

  private final ConcurrentBattleCalculator oddsCalc = new ConcurrentBattleCalculator();
  private Snapshot prev;
  private int round;
  private Map<String, SideAcc> acc = new LinkedHashMap<>();
  private final Map<String, Integer> prevTuv = new HashMap<>();
  private final Map<String, Integer> prevGap = new HashMap<>();
  private final Map<String, Integer> prevDGap = new HashMap<>();
  // Latest ProAI estimate per round|player|territory.
  private final Map<String, AttackDecision> aiDecisions = new HashMap<>();
  private final Map<Integer, List<Path>> ringByRound = new TreeMap<>();
  private final List<String> keptSaves = new ArrayList<>();
  private int roundsFlagged;
  private boolean over;
  private int lastRecordedRound;
  private final List<GamePlayer> turnOrder;
  private final CapitalThreats capitalThreats;
  // Territory owners after the last step, and before the current turn's combat move (walk-ins
  // change the owner at move time, before the battle step).
  private Map<Territory, GamePlayer> owners;
  private Map<Territory, GamePlayer> ownersBeforeCombatMove = Map.of();

  /** A capture still open to recapture: the capturer hasn't had its next turn yet. */
  private record Capture(GamePlayer by, String side, int round) {}

  private final Map<Territory, Capture> openCaptures = new HashMap<>();

  // Per-step state, set in beforeStep().
  private String stepKind;
  @Nullable private GamePlayer stepPlayer;
  private int stepRound;
  private Map<GamePlayer, Integer> pusBefore = Map.of();
  private List<PendingBattle> pendingBattles = List.of();

  GameRecorder(
      final ServerGame game,
      final Path outRoot,
      final String gameId,
      final Options options,
      final DecisionLog decisionLog,
      final Map<String, String> variantBySide,
      final Map<String, Object> config) {
    this.game = game;
    this.data = game.getData();
    this.gameId = gameId;
    this.options = options;
    this.decisionLog = decisionLog;
    this.variantBySide = variantBySide;
    this.dir = outRoot.resolve(gameId);
    this.ringDir = dir.resolve("ring");
    this.savesDir = dir.resolve("saves");
    this.pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      final String side = sideOf(player);
      sideOf.put(player, side);
      playersBySide.computeIfAbsent(side, k -> new ArrayList<>()).add(player);
    }
    this.config = new LinkedHashMap<>(config);
    this.config.put("game_id", gameId);
    this.config.put(
        "sides",
        playersBySide.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    e -> e.getValue().stream().map(GamePlayer::getName).toList(),
                    (a, b) -> a,
                    LinkedHashMap::new)));
    this.config.put("variant_by_side", variantBySide);
    this.turnOrder = turnOrder(data);
    this.config.put("turn_order", turnOrder.stream().map(GamePlayer::getName).toList());
    this.config.put("odds_runs", options.oddsRuns());
    this.config.put(
        "flag_rules",
        Map.of(
            "combat_term_below", -options.flagCombat(), "risky_battle_odds", options.flagOdds()));
    try {
      Files.createDirectories(ringDir);
      Files.createDirectories(savesDir);
      writeConfig();
      out = Files.newBufferedWriter(dir.resolve("records.jsonl"));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    newRoundAccumulators();
    prev = snapshot();
    owners = currentOwners();
    round = data.getSequence().getRound();
    emitRoundRecords(0, false);
    capitalThreats =
        new CapitalThreats(data, oddsCalc, options.oddsRuns(), sideOf::get, this::tuvOf, turnOrder);
  }

  Path dir() {
    return dir;
  }

  /**
   * True once the game is decided. Victory is signalled in the round's last step (EndRound); the
   * engine only marks the game over on the next {@code runNextStep()}, after the round counter has
   * already moved on, so callers should stop on this instead.
   */
  boolean isOver() {
    return over;
  }

  /** The last round a record was written for. */
  int lastRound() {
    return lastRecordedRound;
  }

  private List<GamePlayer> winners() {
    return ((EndRoundDelegate) data.getEndRoundDelegate())
        .getWinners().stream().sorted(Comparator.comparing(GamePlayer::getName)).toList();
  }

  void beforeStep() {
    final GameStep step = data.getSequence().getStep();
    final IDelegate delegate = step.getDelegate();
    stepPlayer = step.getPlayerId();
    stepRound = data.getSequence().getRound();
    stepKind = kindOf(delegate, step.getName());
    pusBefore = pusByPlayer();
    if (stepKind.equals("combat_move")) {
      ownersBeforeCombatMove = new HashMap<>(owners);
    }
    // Bid steps come round every round but only run in round 1; they aren't the player's turn.
    if (stepPlayer != null && !step.hasReachedMaxRunCount()) {
      openCaptures.values().removeIf(c -> c.by().equals(stepPlayer) && c.round() < stepRound);
    }
    if (delegate instanceof PurchaseDelegate
        && !(delegate instanceof BidPurchaseDelegate)
        && stepPlayer != null) {
      saveToRing();
    }
    pendingBattles = delegate instanceof BattleDelegate ? capturePendingBattles() : List.of();
  }

  void afterStep() {
    final Snapshot cur = snapshot();
    classify(prev, cur);
    trackPus();
    for (final DecisionLog.Plan plan : decisionLog.drain()) {
      emitDecisions(plan);
    }
    for (final PendingBattle pending : pendingBattles) {
      resolveBattle(pending, cur);
    }
    pendingBattles = List.of();
    prev = cur;
    trackOwnership();
    if (stepKind.equals("end_turn") && stepPlayer != null) {
      capitalThreats.afterTurn(gameId, stepRound, stepPlayer).forEach(this::write);
    }
    final int nowRound = data.getSequence().getRound();
    if (game.isGameOver() || !winners().isEmpty()) {
      emitRoundRecords(round, true);
      capitalThreats.roundEnded(round);
      over = true;
    } else if (nowRound != round) {
      emitRoundRecords(round, false);
      capitalThreats.roundEnded(round);
      round = nowRound;
    }
  }

  /**
   * Flags a side that lost a capital ({@code capital_lost:<territory>}), and a side whose capture
   * was taken back before the capturing player's next turn ({@code recaptured:<territory>}).
   */
  private void trackOwnership() {
    final Map<Territory, GamePlayer> now = currentOwners();
    for (final Map.Entry<Territory, GamePlayer> e : now.entrySet()) {
      final Territory t = e.getKey();
      final GamePlayer was = owners.get(t);
      final String oldSide = was == null ? null : sideOf.get(was);
      final String newSide = sideOf.get(e.getValue());
      if (Objects.equals(oldSide, newSide)) {
        continue;
      }
      final boolean capital =
          TerritoryAttachment.get(t).map(TerritoryAttachment::isCapital).orElse(false);
      if (capital && oldSide != null) {
        addFlag(oldSide, "capital_lost:" + t.getName());
      }
      final Capture open = openCaptures.remove(t);
      if (open != null && open.side().equals(oldSide)) {
        addFlag(open.side(), "recaptured:" + t.getName());
      }
      if (newSide != null) {
        openCaptures.put(
            t, new Capture(stepPlayer == null ? e.getValue() : stepPlayer, newSide, stepRound));
      }
    }
    owners = now;
  }

  private void addFlag(final String side, final String flag) {
    final SideAcc a = acc.get(side);
    if (a != null && !a.flags.contains(flag)) {
      a.flags.add(flag);
    }
  }

  private Map<Territory, GamePlayer> currentOwners() {
    final Map<Territory, GamePlayer> result = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        result.put(t, t.getOwner());
      }
    }
    return result;
  }

  /** Players in step order, bid steps excluded; the sequence repeats it every round. */
  private static List<GamePlayer> turnOrder(final GameData data) {
    final Set<GamePlayer> order = new LinkedHashSet<>();
    for (final GameStep step : data.getSequence()) {
      if (step.getPlayerId() != null
          && !GameStep.isBidStepName(step.getName())
          && !GameStep.isBidPlaceStepName(step.getName())) {
        order.add(step.getPlayerId());
      }
    }
    return List.copyOf(order);
  }

  /** Writes the final game.json (config plus {@code result}) and drops unflagged ring saves. */
  void finish(final Map<String, Object> result) {
    oddsCalc.cancel();
    oddsCalc.setGameData(null);
    try {
      out.close();
      for (final List<Path> saves : ringByRound.values()) {
        for (final Path save : saves) {
          Files.deleteIfExists(save);
        }
      }
      Files.deleteIfExists(ringDir);
      final Map<String, Object> fullResult = new LinkedHashMap<>(result);
      fullResult.put("rounds_flagged", roundsFlagged);
      fullResult.put("saves_kept", keptSaves);
      fullResult.put("capital_threat_cost_by_round", capitalThreats.costByRound());
      config.put("result", fullResult);
      writeConfig();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---------------------------------------------------------------- snapshots and TUV diffing

  private Snapshot snapshot() {
    final Map<UUID, UnitRec> units = new HashMap<>();
    final Map<String, Integer> tuv = new LinkedHashMap<>();
    final Map<String, Integer> mobile = new LinkedHashMap<>();
    for (final String side : playersBySide.keySet()) {
      tuv.put(side, 0);
      mobile.put(side, 0);
    }
    // TUV is summed per collection membership, independent of the UUID map, so a unit counted in
    // two places (or a type change under one UUID) shows up as a reconcile error.
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection()) {
        record(units, tuv, mobile, u, t.getName());
      }
    }
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      for (final Unit u : p.getUnitCollection()) {
        record(units, tuv, mobile, u, "holding:" + p.getName());
      }
    }
    return new Snapshot(units, tuv, mobile);
  }

  private void record(
      final Map<UUID, UnitRec> units,
      final Map<String, Integer> tuv,
      final Map<String, Integer> mobile,
      final Unit u,
      final String where) {
    final GamePlayer owner = u.getOwner();
    final int cost = costs.getCostsForTuv(owner).getInt(u.getType());
    final boolean infra = Matches.unitIsInfrastructure().test(u);
    units.put(u.getId(), new UnitRec(u.getType().getName(), owner, where, cost, infra));
    final String side = sideOf.get(owner);
    if (side != null) {
      tuv.merge(side, cost, Integer::sum);
      if (!infra) {
        mobile.merge(side, cost, Integer::sum);
      }
    }
  }

  private void classify(final Snapshot before, final Snapshot after) {
    final String actor = stepPlayer == null ? "none" : stepPlayer.getName();
    for (final Map.Entry<UUID, UnitRec> e : before.units().entrySet()) {
      final UnitRec was = e.getValue();
      final UnitRec now = after.units().get(e.getKey());
      final SideAcc wasSide = accOf(was.owner());
      if (now == null) {
        if (wasSide == null) {
          continue;
        }
        if (stepKind.equals("battle")) {
          wasSide.combatLoss += was.cost();
          wasSide.combatLossByTurn.merge(actor, was.cost(), Integer::sum);
        } else {
          wasSide.otherLoss.merge(stepKind, was.cost(), Integer::sum);
        }
        continue;
      }
      final SideAcc nowSide = accOf(now.owner());
      if (wasSide != nowSide) {
        if (wasSide != null) {
          wasSide.capturedOut += was.cost();
        }
        if (nowSide != null) {
          nowSide.capturedIn += now.cost();
        }
      } else if (nowSide != null && now.cost() != was.cost()) {
        // Same side, different valuation (owner with another cost table, or a type change).
        nowSide.otherGain += now.cost() - was.cost();
      }
    }
    for (final Map.Entry<UUID, UnitRec> e : after.units().entrySet()) {
      if (before.units().containsKey(e.getKey())) {
        continue;
      }
      final SideAcc side = accOf(e.getValue().owner());
      if (side == null) {
        continue;
      }
      if (stepKind.equals("purchase")) {
        side.production += e.getValue().cost();
      } else {
        side.otherGain += e.getValue().cost();
      }
    }
  }

  private void trackPus() {
    if (stepPlayer == null || accOf(stepPlayer) == null) {
      return;
    }
    final int delta =
        stepPlayer.getResources().getQuantity(pus) - pusBefore.getOrDefault(stepPlayer, 0);
    if (stepKind.equals("purchase") && delta < 0) {
      accOf(stepPlayer).pusSpent -= delta;
    } else if (stepKind.equals("end_turn") && delta > 0) {
      accOf(stepPlayer).income += delta;
    }
  }

  private Map<GamePlayer, Integer> pusByPlayer() {
    final Map<GamePlayer, Integer> result = new HashMap<>();
    for (final GamePlayer p : sideOf.keySet()) {
      result.put(p, p.getResources().getQuantity(pus));
    }
    return result;
  }

  // ---------------------------------------------------------------- round records

  private void newRoundAccumulators() {
    acc = new LinkedHashMap<>();
    for (final String side : playersBySide.keySet()) {
      acc.put(side, new SideAcc());
    }
  }

  private void emitRoundRecords(final int recordRound, final boolean gameOver) {
    final Snapshot now = prev;
    final List<String> winners =
        gameOver ? winners().stream().map(GamePlayer::getName).toList() : List.of();
    final int allTuv = now.tuvBySide().values().stream().mapToInt(Integer::intValue).sum();
    for (final SideAcc a : acc.values()) {
      if (recordRound > 0
          && combatTerm(a) < -options.flagCombat()
          && !a.flags.contains("combat_term")) {
        a.flags.add(0, "combat_term");
      }
    }
    final boolean flagged = acc.values().stream().anyMatch(a -> !a.flags.isEmpty());
    final List<String> keptNow = flagged ? persistRing(recordRound) : List.of();
    if (flagged) {
      roundsFlagged++;
    }
    for (final Map.Entry<String, List<GamePlayer>> sideEntry : playersBySide.entrySet()) {
      final String side = sideEntry.getKey();
      final SideAcc own = acc.get(side);
      final int tuv = now.tuvBySide().get(side);
      final int oppTuv = allTuv - tuv;
      final int gap = tuv - oppTuv;
      final Map<String, Object> r = new LinkedHashMap<>();
      r.put("type", "round");
      r.put("game_id", gameId);
      r.put("round", recordRound);
      r.put("game_over", gameOver);
      r.put("winners", winners);
      r.put("side", side);
      r.put("variant", variantBySide.get(side));
      r.put("players", sideEntry.getValue().stream().map(GamePlayer::getName).toList());
      r.put("tuv_total", tuv);
      r.put("tuv_mobile", now.mobileBySide().get(side));
      r.put("tuv_by_player", tuvByPlayer(now, side));
      r.put("opp_tuv_total", oppTuv);
      r.put("production_tuv", own.production);
      r.put("pus_spent", own.pusSpent);
      r.put("income", own.income);
      r.put(
          "pus_bank",
          sideEntry.getValue().stream().mapToInt(p -> p.getResources().getQuantity(pus)).sum());
      r.put("territory_production", territoryProduction(side));
      r.put("combat_loss_tuv", own.combatLoss);
      r.put("combat_loss_by_turn", own.combatLossByTurn);
      r.put("other_loss_tuv", own.otherLoss);
      r.put("other_gain_tuv", own.otherGain);
      r.put("captured_in_tuv", own.capturedIn);
      r.put("captured_out_tuv", own.capturedOut);
      r.put("vcs", victoryCities(side));
      r.put("capitals_held", capitalsHeld(side));
      r.put("gap", gap);
      if (recordRound == 0) {
        r.put("d_gap", null);
        r.put("dd_gap", null);
        r.put("d_gap_terms", null);
        r.put("reconcile_error", 0);
        r.put("gap_reconcile_error", 0);
      } else {
        final int dGap = gap - prevGap.get(side);
        final Integer prevD = prevDGap.get(side);
        final Map<String, Integer> terms = new LinkedHashMap<>();
        int oppProduction = 0;
        int oppCombat = 0;
        int oppCapture = 0;
        int oppOther = 0;
        for (final Map.Entry<String, SideAcc> e : acc.entrySet()) {
          if (!e.getKey().equals(side)) {
            final SideAcc o = e.getValue();
            oppProduction += o.production;
            oppCombat += o.combatLoss;
            oppCapture += o.capturedIn - o.capturedOut;
            oppOther += o.otherGain - o.otherLossTotal();
          }
        }
        terms.put("production", own.production - oppProduction);
        terms.put("combat", oppCombat - own.combatLoss);
        terms.put("capture", own.capturedIn - own.capturedOut - oppCapture);
        terms.put("other", own.otherGain - own.otherLossTotal() - oppOther);
        r.put("d_gap", dGap);
        r.put("dd_gap", prevD == null ? null : dGap - prevD);
        r.put("d_gap_terms", terms);
        r.put("reconcile_error", (tuv - prevTuv.get(side)) - own.net());
        r.put(
            "gap_reconcile_error",
            dGap - terms.values().stream().mapToInt(Integer::intValue).sum());
        prevDGap.put(side, dGap);
      }
      r.put("battles_attacked", own.battlesAttacked);
      r.put("flags", own.flags);
      r.put("saves_kept", keptNow);
      write(r);
      prevTuv.put(side, tuv);
      prevGap.put(side, gap);
    }
    flush();
    lastRecordedRound = recordRound;
    pruneRing(recordRound);
    newRoundAccumulators();
  }

  private int combatTerm(final SideAcc own) {
    int oppCombat = 0;
    for (final SideAcc o : acc.values()) {
      if (o != own) {
        oppCombat += o.combatLoss;
      }
    }
    return oppCombat - own.combatLoss;
  }

  private Map<String, Integer> tuvByPlayer(final Snapshot snap, final String side) {
    final Map<String, Integer> result = new LinkedHashMap<>();
    for (final GamePlayer p : playersBySide.get(side)) {
      result.put(p.getName(), 0);
    }
    for (final UnitRec u : snap.units().values()) {
      if (side.equals(sideOf.get(u.owner()))) {
        result.merge(u.owner().getName(), u.cost(), Integer::sum);
      }
    }
    return result;
  }

  private int victoryCities(final String side) {
    return ownedTerritories(side)
        .mapToInt(
            t -> TerritoryAttachment.get(t).map(TerritoryAttachment::getVictoryCity).orElse(0))
        .sum();
  }

  private int territoryProduction(final String side) {
    return ownedTerritories(side)
        .mapToInt(t -> TerritoryAttachment.get(t).map(TerritoryAttachment::getProduction).orElse(0))
        .sum();
  }

  private List<String> capitalsHeld(final String side) {
    return ownedTerritories(side)
        .filter(t -> TerritoryAttachment.get(t).map(TerritoryAttachment::isCapital).orElse(false))
        .map(Territory::getName)
        .sorted()
        .toList();
  }

  private Stream<Territory> ownedTerritories(final String side) {
    return data.getMap().getTerritories().stream()
        .filter(t -> side.equals(sideOf.get(t.getOwner())));
  }

  // ---------------------------------------------------------------- battles

  private List<PendingBattle> capturePendingBattles() {
    final BattleTracker tracker = AbstractMoveDelegate.getBattleTracker(data);
    final List<IBattle> battles = new ArrayList<>();
    for (final IBattle.BattleType type : IBattle.BattleType.values()) {
      battles.addAll(tracker.getPendingBattles(type));
    }
    if (battles.isEmpty()) {
      return List.of();
    }
    if (battles.stream().anyMatch(GameRecorder::oddsApplicable)) {
      // The calculator works on its own copy of the game; refresh it to the current board.
      oddsCalc.setGameData(data).join();
    }
    final List<PendingBattle> result = new ArrayList<>();
    for (final IBattle battle : battles) {
      result.add(describeBattle(battle));
    }
    return result;
  }

  private static boolean oddsApplicable(final IBattle battle) {
    return battle.getBattleType() == IBattle.BattleType.NORMAL
        && !battle.getAttackingUnits().isEmpty()
        && !battle.getDefendingUnits().isEmpty();
  }

  private PendingBattle describeBattle(final IBattle battle) {
    final Territory t = battle.getTerritory();
    final GamePlayer attacker = battle.getAttacker();
    final GamePlayer defender = battle.getDefender();
    final String attackerSide = sideOf.get(attacker);
    final Collection<Unit> attacking = List.copyOf(battle.getAttackingUnits());
    final Collection<Unit> defending = List.copyOf(battle.getDefendingUnits());
    final Collection<Unit> bombarding = List.copyOf(battle.getBombardingUnits());
    final TerritoryAttachment ta = TerritoryAttachment.get(t).orElse(null);
    final String capitalOf = ta == null ? null : ta.getCapital().orElse(null);
    final int vc = ta == null ? 0 : ta.getVictoryCity();
    final int attackerTuv = tuvOf(attacking);
    final Integer sideTuv = attackerSide == null ? null : prev.tuvBySide().get(attackerSide);

    final Map<String, Object> stakes = new LinkedHashMap<>();
    stakes.put("target_is_capital_of", capitalOf);
    stakes.put("target_has_factory", t.anyUnitsMatch(Matches.unitCanProduceUnits()));
    stakes.put("target_production", ta == null ? 0 : ta.getProduction());
    stakes.put("target_vc", vc);
    stakes.put("attacker_committed_tuv", attackerTuv);
    stakes.put(
        "attacker_committed_frac_side_tuv",
        sideTuv == null || sideTuv == 0 ? null : round4((double) attackerTuv / sideTuv));
    stakes.put("defender_tuv", tuvOf(defending));

    final Map<String, Object> pre = new LinkedHashMap<>();
    pre.put("attackers", countByType(attacking));
    pre.put("defenders", countByType(defending));
    pre.put("bombard", countByType(bombarding));

    final Map<String, Object> r = new LinkedHashMap<>();
    r.put("type", "battle");
    r.put("game_id", gameId);
    r.put("round", stepRound);
    r.put("turn_player", stepPlayer == null ? null : stepPlayer.getName());
    r.put("battle_id", battle.getBattleId().toString());
    r.put("attacker", attacker.getName());
    r.put("attacker_side", attackerSide);
    r.put("attacker_variant", variantBySide.get(attackerSide));
    r.put("defender", defender == null ? null : defender.getName());
    r.put("defender_side", defender == null ? null : sideOf.get(defender));
    r.put("territory", t.getName());
    r.put("battle_type", battle.getBattleType().name());
    r.put("is_water", t.isWater());
    r.put("amphibious", battle.isAmphibious());
    r.put("stakes", stakes);
    r.put("pre", pre);

    final PendingBattle pending =
        new PendingBattle(
            battle,
            attacking.stream().map(Unit::getId).toList(),
            defending.stream().map(Unit::getId).toList(),
            capitalOf != null || vc > 0,
            r);
    if (oddsApplicable(battle) && defender != null) {
      oddsCalc.setAmphibious(battle.isAmphibious());
      final AggregateResults results =
          oddsCalc.calculate(
              attacker,
              defender,
              t,
              attacking,
              defending,
              bombarding,
              TerritoryEffectHelper.getEffects(t),
              false,
              options.oddsRuns());
      final Map<String, Object> odds = new LinkedHashMap<>();
      if (results.getRollCount() > 0) {
        pending.harnessWin = results.getAttackerWinPercent();
        pending.expectedSwing =
            results.getAverageTuvSwing(attacker, attacking, defender, defending, data);
        odds.put("win", round4(pending.harnessWin));
        odds.put("draw", round4(results.getDrawPercent()));
        odds.put("lose", round4(results.getDefenderWinPercent()));
        odds.put("exp_tuv_swing", round4(pending.expectedSwing));
        odds.put("exp_rounds", round4(results.getAverageBattleRoundsFought()));
      }
      odds.put("runs", results.getRollCount());
      r.put("odds_harness", odds);
    } else {
      r.put("odds_harness", null);
    }
    pending.ownerBefore = ownersBeforeCombatMove.getOrDefault(t, t.getOwner()).getName();
    return pending;
  }

  private void resolveBattle(final PendingBattle pending, final Snapshot after) {
    final Map<String, Object> r = pending.record;
    final AttackDecision ai =
        aiDecisions.get(key(stepRound, pending.attacker.getName(), pending.territory.getName()));
    if (ai == null) {
      r.put("odds_ai", null);
    } else {
      final Map<String, Object> oddsAi = new LinkedHashMap<>();
      oddsAi.put("win", ai.winPercentage() == null ? null : round4(ai.winPercentage() / 100));
      oddsAi.put("tuv_swing", ai.tuvSwing() == null ? null : round4(ai.tuvSwing()));
      oddsAi.put("has_land_remaining", ai.hasLandUnitRemaining());
      oddsAi.put("strafing", ai.strafing());
      oddsAi.put("bombers", ai.bombers());
      oddsAi.put("accepted", ai.accepted());
      oddsAi.put("runs", ai.runs());
      oddsAi.put("can_hold", ai.canHold());
      oddsAi.put(
          "can_hold_branch", ai.canHoldTrace() == null ? "default" : ai.canHoldTrace().branch());
      oddsAi.put("src", "combat_move_hook");
      r.put("odds_ai", oddsAi);
    }

    final String attackerSide = sideOf.get(pending.attacker);
    int attackerLost = 0;
    final Map<String, Integer> survivors = new TreeMap<>();
    boolean anyOutside = false;
    for (final UUID id : pending.attackers) {
      final UnitRec was = prev.units().get(id);
      final UnitRec now = after.units().get(id);
      if (was == null) {
        continue;
      }
      if (now == null) {
        attackerLost += was.cost();
      } else {
        survivors.merge(now.type(), 1, Integer::sum);
        anyOutside |= !now.where().equals(pending.territory.getName());
      }
    }
    int defenderLost = 0;
    int defenderCaptured = 0;
    for (final UUID id : pending.defenders) {
      final UnitRec was = prev.units().get(id);
      final UnitRec now = after.units().get(id);
      if (was == null) {
        continue;
      }
      if (now == null) {
        defenderLost += was.cost();
      } else if (!Objects.equals(sideOf.get(was.owner()), sideOf.get(now.owner()))) {
        defenderCaptured += was.cost();
      }
    }
    final IBattle.WhoWon whoWon = pending.battle.getWhoWon();
    final String ownerAfter = pending.territory.getOwner().getName();
    final int actualSwing = defenderLost - attackerLost;
    final Map<String, Object> outcome = new LinkedHashMap<>();
    outcome.put("winner", whoWon.name());
    outcome.put("attacker_lost_tuv", attackerLost);
    outcome.put("defender_lost_tuv", defenderLost);
    outcome.put("defender_captured_tuv", defenderCaptured);
    outcome.put("actual_swing", actualSwing);
    outcome.put(
        "conquered",
        !ownerAfter.equals(pending.ownerBefore)
            && attackerSide != null
            && attackerSide.equals(sideOf.get(pending.territory.getOwner())));
    outcome.put("retreated", whoWon != IBattle.WhoWon.ATTACKER && anyOutside);
    outcome.put("owner_before", pending.ownerBefore);
    outcome.put("owner_after", ownerAfter);
    outcome.put("attackers_survived", survivors);
    r.put("outcome", outcome);
    r.put(
        "luck", pending.expectedSwing == null ? null : round4(actualSwing - pending.expectedSwing));

    final boolean strafing = ai != null && ai.strafing();
    final boolean flagged =
        pending.risksCapitalOrVc
            && pending.harnessWin != null
            && pending.harnessWin < options.flagOdds()
            && whoWon != IBattle.WhoWon.ATTACKER
            && !strafing;
    r.put("flagged", flagged);
    if (attackerSide != null) {
      final SideAcc a = acc.get(attackerSide);
      if (pending.battle.getBattleType() == IBattle.BattleType.NORMAL) {
        a.battlesAttacked++;
      }
      if (flagged) {
        a.flags.add("risky_battle_lost:" + pending.territory.getName());
      }
    }
    write(r);
  }

  // ---------------------------------------------------------------- ProAI decisions

  private void emitDecisions(final DecisionLog.Plan plan) {
    final String side = sideOf.get(data.getPlayerList().getPlayerId(plan.player()));
    for (final AttackDecision d : plan.decisions()) {
      aiDecisions.put(key(plan.round(), plan.player(), d.territory()), d);
      final Map<String, Object> r = new LinkedHashMap<>();
      r.put("type", "ai_attack");
      r.put("game_id", gameId);
      r.put("round", plan.round());
      r.put("player", plan.player());
      r.put("side", side);
      r.put("variant", variantBySide.get(side));
      r.put("simulated", plan.simulated());
      r.put("territory", d.territory());
      r.put("accepted", d.accepted());
      r.put("attacking_units", d.attackingUnits());
      r.put("bombers", d.bombers());
      r.put("strafing", d.strafing());
      r.put("win", d.winPercentage() == null ? null : round4(d.winPercentage() / 100));
      r.put("tuv_swing", d.tuvSwing() == null ? null : round4(d.tuvSwing()));
      r.put("has_land_remaining", d.hasLandUnitRemaining());
      r.put("max_win", d.maxWinPercentage() == null ? null : round4(d.maxWinPercentage() / 100));
      r.put("can_hold", d.canHold());
      r.put("value", round4(d.value()));
      r.put("runs", d.runs());
      r.put("assumed_defenders", d.assumedDefenders());
      final ProDecisionListener.CanHoldTrace trace = d.canHoldTrace();
      r.put("can_hold_branch", trace == null ? "default" : trace.branch());
      r.put("can_hold_pass", trace == null ? null : trace.pass());
      if (trace == null || trace.counterWinPercentage() == null) {
        r.put("can_hold_counter", null);
      } else {
        final Map<String, Object> counter = new LinkedHashMap<>();
        counter.put("win", round4(trace.counterWinPercentage() / 100));
        counter.put("tuv_swing", round4(trace.counterTuvSwing()));
        counter.put("has_land_remaining", trace.counterHasLandUnitRemaining());
        counter.put("runs", trace.counterRuns());
        counter.put("my_defenders", trace.myDefenders());
        counter.put("enemy_attackers", trace.enemyAttackers());
        r.put("can_hold_counter", counter);
      }
      write(r);
    }
  }

  private static String key(final int round, final String player, final String territory) {
    return round + "|" + player + "|" + territory;
  }

  // ---------------------------------------------------------------- saves

  private void saveToRing() {
    final Path file =
        ringDir.resolve(String.format("r%03d-%s-purchase.tsvg", stepRound, stepPlayer.getName()));
    game.saveGame(file);
    ringByRound.computeIfAbsent(stepRound, k -> new ArrayList<>()).add(file);
  }

  /** Moves the ring saves of {@code flaggedRound} and the round before it into saves/. */
  private List<String> persistRing(final int flaggedRound) {
    final List<String> kept = new ArrayList<>();
    for (final int r : List.of(flaggedRound - 1, flaggedRound)) {
      final List<Path> saves = ringByRound.remove(r);
      if (saves == null) {
        continue;
      }
      for (final Path save : saves) {
        try {
          final Path target = savesDir.resolve(save.getFileName());
          Files.move(save, target, StandardCopyOption.REPLACE_EXISTING);
          // The engine writes saves owner-only; let other users of the box read them.
          Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-rw-r--"));
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
        kept.add("saves/" + save.getFileName());
      }
    }
    keptSaves.addAll(kept);
    return kept;
  }

  /** Keeps only the ring saves of the round just ended; the next round adds its own. */
  private void pruneRing(final int endedRound) {
    final List<Integer> old = ringByRound.keySet().stream().filter(r -> r < endedRound).toList();
    for (final int r : old) {
      for (final Path save : ringByRound.remove(r)) {
        try {
          Files.deleteIfExists(save);
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
  }

  // ---------------------------------------------------------------- helpers

  @Nullable
  private SideAcc accOf(final GamePlayer owner) {
    final String side = sideOf.get(owner);
    return side == null ? null : acc.get(side);
  }

  private int tuvOf(final Collection<Unit> units) {
    int sum = 0;
    for (final Unit u : units) {
      sum += costs.getCostsForTuv(u.getOwner()).getInt(u.getType());
    }
    return sum;
  }

  private static Map<String, Integer> countByType(final Collection<Unit> units) {
    final Map<String, Integer> counts = new TreeMap<>();
    for (final Unit u : units) {
      counts.merge(u.getType().getName(), 1, Integer::sum);
    }
    return counts;
  }

  private static String kindOf(final IDelegate delegate, final String stepName) {
    if (delegate instanceof PurchaseDelegate) {
      return "purchase";
    } else if (delegate instanceof BattleDelegate) {
      return "battle";
    } else if (delegate instanceof AbstractMoveDelegate) {
      return GameStep.isNonCombatMoveStepName(stepName) ? "noncombat_move" : "combat_move";
    } else if (delegate instanceof AbstractPlaceDelegate) {
      return "place";
    } else if (delegate instanceof AbstractEndTurnDelegate) {
      return "end_turn";
    }
    return delegate.getClass().getSimpleName();
  }

  static String sideOf(final GamePlayer player) {
    final Collection<String> alliances =
        player.getData().getAllianceTracker().getAlliancesPlayerIsIn(player);
    return alliances.isEmpty() ? player.getName() : String.join("+", alliances);
  }

  private static double round4(final double value) {
    return Math.round(value * 10_000.0) / 10_000.0;
  }

  private void writeConfig() throws IOException {
    Files.writeString(
        dir.resolve("game.json"),
        new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(config));
  }

  private void write(final Map<String, Object> record) {
    try {
      out.write(GSON.toJson(record));
      out.newLine();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void flush() {
    try {
      out.flush();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
