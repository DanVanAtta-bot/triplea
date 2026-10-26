package games.strategy.triplea.ai.pro;

/**
 * Experimental, individually toggleable changes to Hard AI behavior. Each {@link ProAi} instance
 * carries its own set, so differently patched AIs can play in one game; the empty set is the stock
 * Hard AI. Gate a change with {@link ProData#isPatchOn(ProAiPatch)}.
 */
public enum ProAiPatch {
  /** Changes nothing; only proves a gate is reached from the AI's turn logic. */
  NO_OP,
}
