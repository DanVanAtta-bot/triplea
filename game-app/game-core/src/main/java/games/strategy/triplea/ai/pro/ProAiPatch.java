package games.strategy.triplea.ai.pro;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

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
   * Purchase picks a strike round T: the kill-strike clock (10-round horizon) of the player's
   * alliance on the nearest live enemy capital, if it beats the enemies' clock on the player's
   * capital. At each factory with a land route to that capital it buys only land units fast enough
   * to arrive by T, and of those the attack units with the most combat power per PU: artillery and
   * infantry while T is far, only fast units once slow ones can't arrive. Stock otherwise.
   */
  STRIKE_TIMED_PURCHASE,
  // AI-1: Dan's strategy model, one gate per component. AI-1 = all DAN_* gates on (see AI1).
  /**
   * Plan (Dan's model, sections 4 and 10): a player whose capital has no enemy clock (none within
   * 10 rounds, re-checked each round) picks a strike: the nearest enemy capital by land from its
   * factories (distance d), landing at random in round r + d, r + d + 1 or r + d + 3. Until then,
   * unless it must defend first (DAN_COOKED's rule), each factory buys only land units that reach
   * the target by the strike round, the most combat power per PU among them; no offensive navy when
   * the capital marches on the target; and the target's value is tripled. A player whose capital
   * gets a clock drops its plan. Without a live plan it strikes only when its clock is 3 or less
   * and beats the enemies'.
   */
  DAN_PLAN,
  /**
   * Don't get cooked (section 3): when the enemies' clock on my capital is 2 or less, or shorter
   * than my strike and at most 4, purchase first buys the cheapest defence at the capital that
   * leaves no kill up to my strike round (at most 4, at least 2), assuming the best defensive buy
   * there on every later turn.
   */
  DAN_COOKED,
  /**
   * Objectives (section 9): capitals and victory cities are the only targets in the value map (not
   * factories), and other territories' income counts only for the rounds before the decisive round
   * (my strike or the enemies' clock) that it can still turn into force.
   */
  DAN_OBJECTIVES,
  /**
   * Fronts (section 8): per enemy power, the sum of delivered production, TUV traded and pin
   * balance. Where my side out-produces the front (ratio rho > 1), combat move accepts even land
   * trades: my losses count 1 / min(rho, 2) in the attack keep test (not at sea or for landings).
   */
  DAN_FRONTS,
  /**
   * Pins (section 6): clocks leave out the units each side's threats hold at its objectives; NCM
   * keeps a stack that pins an enemy objective when freeing the units it holds would bring the
   * enemies' clock on my capital to my strike round or less; and a holdable place territory the
   * enemy can attack is padded with infantry (cheap hit points against strafes).
   */
  DAN_PINS,
  /**
   * Supply lines (Dan's A6/A7): strength each side can have at a territory by each turn, from units
   * on the board, then purchases from the factories feeding it (capped by their production; a
   * factory with no land route only through a transport shuttle with naval dominance on the route),
   * with pinned slow units left behind. Replaces my own kill-strike clocks in the other DAN gates
   * (the enemies' clock on my capital stays the classic one), sets each front's delivery delay, and
   * scales a non-objective capture's worth in combat by the turns (of 3) my arrival there keeps up
   * with the enemies'.
   */
  DAN_SUPPLY,
  /**
   * Factories (Dan's A7, "the reason to make factories"): before the land buy, buys a factory where
   * it adds the most strength arriving at the strike target (or nearest enemy capital) within 6
   * rounds, if that gain is worth at least twice the factory's PUs, the territory produces 3 or
   * more, has a land route to the target and no factory of mine next to it, and holds for 2 turns.
   */
  DAN_FACTORY,
  /**
   * Bug fix. When non-combat move finds the capital without local land superiority (enemy 2-3
   * away), it switches to a copy of the territory manager for a defensive second pass, but stock
   * keeps writing the capital-area values into, and executing the moves of, the first pass's map
   * (the copy's territories are separate objects), so the defensive stance never takes effect. The
   * gate re-binds the move map to the copy's. Counts one hit per defensive second pass.
   */
  NCM_DEFENSIVE_STANCE_FIX,
  ;

  /** AI-1: Dan's strategy model with every component on. */
  public static final Set<ProAiPatch> AI1 =
      Collections.unmodifiableSet(
          EnumSet.of(
              DAN_PLAN, DAN_COOKED, DAN_OBJECTIVES, DAN_FRONTS, DAN_PINS, DAN_SUPPLY, DAN_FACTORY));
}
