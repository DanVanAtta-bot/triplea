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
  /**
   * Combat move no longer counts a captured territory as holdable when the enemy counter-attack
   * likely wins (at least {@code minWinPercentage}) just because it loses TUV or leaves no land
   * units. It must lose TUV even after adding the win odds times the territory's worth.
   */
  CAN_HOLD_TRADE,
  /**
   * Simulates every battle at least {@code ProOddsCalculator.ODDS_MORE_RUNS_FLOOR} times. Stock
   * runs {@code max(16, 100 - smaller army)}, so big battles get the fewest runs and their win%
   * noise straddles the 75%/95% cutoffs.
   */
  ODDS_MORE_RUNS,
  /**
   * The purchase-phase turn simulation plays a land battle that the attacker wins less than {@code
   * winPercentage} of the time (95%, dice 90%) as lost: the territory isn't taken, every defender
   * survives, and the attacking land units are gone. Stock plays every battle as won, so purchase,
   * placement and the stored non-combat plan never hedge against a planned capture failing.
   */
  PURCHASE_SIM_ODDS,
  /**
   * The purchase-phase turn simulation no longer gives the attacker a territory that it can't take
   * even when the battle is won: a strategic bombing raid, or a land battle whose average outcome
   * leaves no attacking land unit. Stock transfers ownership after every simulated battle.
   */
  PURCHASE_SIM_NO_PHANTOM,
  /**
   * The purchase no longer plans units or factories for a territory the simulated turn conquers.
   * Stock asks the real board's battle tracker, which has no conquests yet during purchase, so it
   * plans production in captured or liberated factories (eg Southern Europe retaken) and factories
   * on fresh captures; the engine refuses both ("conquered this turn") and the units are lost.
   */
  PURCHASE_SIM_CONQUERED,
}
