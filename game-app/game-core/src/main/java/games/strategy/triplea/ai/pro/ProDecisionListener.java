package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GamePlayer;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Observes Hard AI decisions for measurement. Implementations get read-only copies of what the AI
 * already computed and must not feed anything back; the AI behaves the same with or without one.
 */
public interface ProDecisionListener {

  /**
   * Called once per combat-move planning pass, just before the planned moves are executed. During
   * the purchase step that pass runs on a simulated copy of the game ({@code simulated}); the real
   * combat-move step then replays the stored plan without calling this again.
   */
  void combatMovePlanned(
      GamePlayer player, int round, boolean simulated, List<AttackDecision> decisions);

  /**
   * One territory the AI considered attacking. {@code winPercentage}/{@code tuvSwing} are the AI's
   * last estimate against its assumed (max) defenders, or null when it never estimated this target.
   * {@code runs} is the calculator run count implied by ProAI's formula for that estimate (max(16,
   * 100 - smaller side)), or null when the estimate skipped the calculator. {@code
   * assumedDefenders} counts {@code getMaxEnemyDefenders} by unit type. {@code canHoldTrace} says
   * which rule last set {@code canHold}; null means none did (the constructor default, true).
   */
  record AttackDecision(
      String territory,
      boolean accepted,
      int attackingUnits,
      int bombers,
      boolean strafing,
      @Nullable Double winPercentage,
      @Nullable Double tuvSwing,
      @Nullable Boolean hasLandUnitRemaining,
      @Nullable Double maxWinPercentage,
      boolean canHold,
      double value,
      @Nullable Integer runs,
      Map<String, Integer> assumedDefenders,
      @Nullable CanHoldTrace canHoldTrace) {}

  /**
   * The rule in {@code determineTerritoriesThatCanBeHeld} that last set {@code canHold}, on
   * planning pass {@code pass} (1 = all conquerable targets, 2 = targets kept after selection).
   * Branches: {@code strafing}, {@code not_worth_holding} (value below 0.75 x attackers' origin
   * value), {@code no_counter_attackers}, and for the counterattack check {@code
   * counter_no_land_left}, {@code counter_tuv_loss}, {@code counter_win_below_min} (true, first
   * matching condition) or {@code counter_wins} (false). The counter fields are the AI's own
   * counterattack estimate, null when there was none; {@code counterRuns} is the formula run count,
   * null when the calculator was skipped.
   */
  record CanHoldTrace(
      String branch,
      int pass,
      @Nullable Double counterWinPercentage,
      @Nullable Double counterTuvSwing,
      @Nullable Boolean counterHasLandUnitRemaining,
      @Nullable Integer counterRuns,
      int myDefenders,
      int enemyAttackers) {}
}
