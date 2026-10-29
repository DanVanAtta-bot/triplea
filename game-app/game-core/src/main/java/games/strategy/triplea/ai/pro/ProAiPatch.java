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
  /**
   * Combat move: once the worth-it check has dropped an attack, retries the amphibious attacks on
   * enemy capitals, factories or holdable targets that target selection removed while that attack
   * was still planned, in attack-value order, highest value first, keeping each one only if every
   * planned attack still succeeds. Stock removes the newest target whenever the set fails, even
   * when the failing target is an earlier one that is later dropped, so the freed units and
   * transports idle (eg: a capital in reach of loaded transports is skipped for a doomed attack on
   * another capital).
   */
  AMPHIB_READD_DROPPED,
  /**
   * Variant of {@link #AMPHIB_READD_DROPPED} that retries every land target selection removed, not
   * only amphibious ones. Needs AMPHIB_READD_DROPPED on.
   */
  ATTACK_READD_DROPPED,
  /**
   * Combat move: a transport may end its move in a sea zone holding enemy units and unload from it
   * (an amphibious assault behind a sea battle, which the engine allows), if that zone is itself
   * attacked in the same combat move and wins at least {@code winPercentage}. The blocked zone is
   * tried before the landings that need it. Stock only moves transports through and unloads from
   * enemy-free zones, so a single blocker stops every landing behind it.
   */
  AMPHIB_CAN_OPENER,
  /**
   * Narrows {@link #AMPHIB_CAN_OPENER} to landings worth a sea battle: a blocked zone only offers
   * enemy capitals and factories as targets, and a landing behind it must be a capital, a factory,
   * or holdable. Without it the UK opens 6 SZ for small landings in Western Europe or Norway that
   * the German counter-attack retakes, and the extra options change attack estimates even when no
   * landing uses them. Needs AMPHIB_CAN_OPENER on.
   */
  AMPHIB_CAN_OPENER_VALUABLE,
  /**
   * Spends a bid on units that change this player's first-round battles (see {@link
   * ProBidPlanner}): next to a target the combat move would attack, or could attack with them, and
   * in own territories an earlier-moving enemy threatens. Stock spreads one unit per territory by
   * long-run territory value, so a bid rarely changes a round-1 battle. Leftover PUs go to stock.
   */
  BID_PLACE_FOR_R1,
  /**
   * Bug fix. Sea-zone defence purchases start from the free deck on own carriers already in the
   * zone (own carrier capacity minus own carrier-landing air, as the place delegate counts it).
   * Stock starts from {@code Math.min(0, unusedCarrierCapacity)}, so free deck counts only after a
   * carrier is bought in the same loop and the AI never buys fighters for an existing carrier.
   * Counts a hit per sea zone whose starting free deck it raises above 0.
   */
  PURCHASE_CARRIER_DECK_FIX,
  /**
   * Non-combat move parks one warship as a blocker in a sea zone that cuts every route enemy
   * transports have to an own or allied land territory they could otherwise take by landing: the
   * enemy's max attack wins with its amphibious units and not without them, and no enemy transport
   * can reach an unload zone next to it once that zone is occupied. Uses the cheapest warship left
   * after the defensive moves, only in a zone the enemy can't attack; combat move frees one from a
   * sea attack that wins without it when no idle warship can block. Stock never moves a sea unit
   * for its blocking effect (the one-unit blocker rule skips sea zones). Counts a hit per blocker
   * freed or placed.
   */
  SEA_BLOCKER,
  /**
   * Combat move sends a bomber to raid an enemy factory without AA (no risk to the bomber) instead
   * of joining a land attack that still wins without it, and considers every unused bomber for a
   * raid. Stock assigns bombers to attacks before it looks at raids, and its raid loop stops at the
   * first bomber that is already attacking, so other free bombers never raid. Counts a hit per
   * bomber moved to a raid.
   */
  SBR_WHEN_FREE,
  /**
   * Combat move strafes land attacks whose expected survivors the counter-attack retakes (judged on
   * the units actually assigned, not stock's canHold on every unit that could attack): when
   * fighting 1-2 rounds and retreating gains more TUV than taking the territory (battle TUV swing
   * plus the territory's worth times win%, minus 2/3 of the counter-attack's TUV swing against the
   * land units left there) by {@code ProCombatMoveAi.STRAFE_MARGIN}, the attackers retreat after
   * those rounds to the territory holding the most of their side's units. Stock never retreats from
   * such an attack, takes the territory and loses the units to the counter-attack (forum A6; LHTR
   * Germany into Karelia next to the Archangel stack).
   */
  STRAFE_CANT_HOLD,
  /**
   * The purchase leaves production slots for units still held from earlier turns, on maps where
   * unplaced units live (eg v3, Global 1940). Stock ignores them and buys a full load, so the held
   * units and the surplus share the slots and the overflow stays held turn after turn.
   */
  PURCHASE_HELD_UNITS,
  /**
   * National objectives count in territory value: a territory whose side decides whether an
   * objective is met (mine completed or kept, the enemy's broken or denied) adds the objective's
   * PUs to its production in the attack, defence, move and purchase-simulation values ({@link
   * games.strategy.triplea.ai.pro.util.ProNationalObjectiveUtils}). Stock values only territory
   * production, so a 0-PU island that completes a 5-PU objective is worth nothing.
   */
  NO_VALUE,
  /**
   * Non-combat move keeps only as many of the land units it sends to (or keeps in) my capital for
   * their value as the capital needs to hold against the enemies' max next-turn attack (enemy win
   * at most 5%, no TUV gain), cheapest defence per PU first, and sends the rest to their best-value
   * holdable territory instead. Stock sends every leftover land unit to the highest-value holdable
   * territory, which is usually the capital, so it ends far above what it needs (champion-7
   * self-play: Moscow at a median 2.4x the German strength in reach). Counts a hit per released
   * unit.
   */
  CAPITAL_ENOUGH,
  /**
   * Combat move keeps the low-value attacks (value at most 8, within 3 of my capital) that stock
   * drops whenever the capital lacks local land superiority, as long as the capital still holds
   * against the enemies' max counter-attack after all attacks (enemy win at most 5%, no TUV gain,
   * counting stock's capital-check defenders); otherwise it drops them, lowest value first. Counts
   * a hit per kept attack.
   */
  CAPITAL_ENOUGH_TRADE,
  ;

  /** AI-1: Dan's strategy model with every component on. */
  public static final Set<ProAiPatch> AI1 =
      Collections.unmodifiableSet(
          EnumSet.of(
              DAN_PLAN, DAN_COOKED, DAN_OBJECTIVES, DAN_FRONTS, DAN_PINS, DAN_SUPPLY, DAN_FACTORY));
}
