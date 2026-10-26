package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GamePlayer;
import java.util.List;
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
      double value) {}
}
