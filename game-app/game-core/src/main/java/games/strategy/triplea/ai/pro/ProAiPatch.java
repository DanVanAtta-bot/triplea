package games.strategy.triplea.ai.pro;

/**
 * Experimental, individually toggleable changes to Hard AI behavior. Each {@link ProAi} instance
 * carries its own set, so differently patched AIs can play in one game; the empty set is the stock
 * Hard AI. Gate a change with {@link ProData#isPatchOn(ProAiPatch)}.
 */
public enum ProAiPatch {
  /** Changes nothing; only proves a gate is reached from the AI's turn logic. */
  NO_OP,
  /**
   * Combat move counts a captured territory as holdable only if the enemy counter-attack wins less
   * than {@code 100 - minWinPercentage} (25%, dice 35%) instead of less than {@code
   * minWinPercentage} (75%, dice 65%). Counts a hit only when that flips the judgment.
   */
  CAN_HOLD_STRICT,
}
