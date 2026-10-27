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
  /**
   * Land purchases for a territory the enemy can attack score units as defenders without crediting
   * attack support: defender buys rank infantry over artillery, and the non-fodder pick ranks
   * armour over artillery. Stock counts artillery's attack bonus even when the units will next
   * defend, so a front-line power (Russia) bought artillery instead of armour or infantry. Counts a
   * hit per pick whose scoring this changes.
   */
  BUY_DEFENSIVE_LAND_MIX,
  /**
   * Purchase buys no warships for a sea zone once own and allied warships there already match the
   * enemy warships that can reach it: the rest of the threat is air, which more ships answer badly.
   * Stock keeps buying until its fleet holds against every plane in range, so the UK hoarded
   * destroyers and carriers. Counts a hit each time it stops a warship purchase.
   */
  BUY_LESS_IDLE_NAVY,
  /**
   * Purchase buys at most one new transport per sea zone a turn unless land units are still waiting
   * at the coast after the existing transports are filled; the remaining production buys land units
   * that wait for the transports. Stock pairs every new transport with fresh cargo until the PUs
   * run out, so the US spent half its money on navy. Counts a hit per transport skipped.
   */
  BUY_TRANSPORTS_ON_DEMAND,
  /**
   * Log only: at purchase, runs the next-turn kill test ({@code ProKillUtils}) and counts a hit
   * when an enemy capital passes it. Changes no decision.
   */
  KILL_DETECT,
  /**
   * When the next-turn kill test finds an enemy capital, purchase places at the factories that can
   * reach it next turn first, and buys there only units fast enough to join the attack.
   */
  KILL_PURCHASE,
  /**
   * Log only: at purchase, logs the kill-strike clocks ({@code ProClockUtils}) of the player's
   * alliance against each live enemy capital and of its enemies against its own capital. Counts a
   * hit per purchase. Changes no decision.
   */
  CLOCK_LOG,
  /**
   * When the enemies' kill-strike clock on the player's capital is at most {@code
   * ProPurchaseAi.CLOCK_RESERVE_ROUNDS}, purchase first buys the best defensive land units the
   * capital can place, as the clock's defence bound assumes it will.
   */
  CAPITAL_CLOCK_RESERVE,
  /**
   * Purchase picks a strike round T: the kill-strike clock of the player's alliance on the nearest
   * live enemy capital, if the enemies' clock on the player's capital is longer. At each factory
   * with a land route to that capital, it buys only land units fast enough to arrive by T (so
   * infantry while T is far, then only fast units). Stock purchase when there is no such T.
   */
  STRIKE_TIMED_PURCHASE,
}
