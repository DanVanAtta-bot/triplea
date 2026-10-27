package games.strategy.triplea.ai.harness;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * {@code --decisive N:X:Y}: ends a two-side game once one side has been decisively ahead at N
 * consecutive round ends. Side A is decisively ahead of B when A's TUV is more than X% above B's,
 * A's territory production is more than Y% above B's, A holds at least one of B's capitals, and
 * none of A's own capitals is held by an enemy.
 */
final class DecisiveRule {

  record Spec(int rounds, int tuvLeadPercent, int productionLeadPercent) {
    static Spec parse(final String value) {
      final String[] parts = value.split(":");
      if (parts.length != 3) {
        throw new IllegalArgumentException(
            "--decisive needs rounds:tuvLead%:productionLead%: " + value);
      }
      final Spec spec =
          new Spec(
              Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
      if (spec.rounds < 1 || spec.tuvLeadPercent < 0 || spec.productionLeadPercent < 0) {
        throw new IllegalArgumentException("--decisive needs rounds >= 1 and leads >= 0: " + value);
      }
      return spec;
    }

    @Override
    public String toString() {
      return rounds + ":" + tuvLeadPercent + ":" + productionLeadPercent;
    }
  }

  /** One side at a round end. Capitals count territories, not players. */
  record SideState(int tuv, int production, int capitalsLost, int enemyCapitalsHeld) {}

  private final Spec spec;
  @Nullable private String leader;
  private int streak;

  DecisiveRule(final Spec spec) {
    this.spec = spec;
  }

  Spec spec() {
    return spec;
  }

  /**
   * Feeds the state at one round end; returns the side that has now been decisively ahead for
   * {@code rounds} consecutive round ends, else null.
   */
  @Nullable
  String roundEnded(final Map<String, SideState> bySide) {
    if (bySide.size() != 2) {
      throw new IllegalStateException("--decisive needs exactly two sides: " + bySide.keySet());
    }
    final List<String> sides = List.copyOf(bySide.keySet());
    final SideState first = bySide.get(sides.get(0));
    final SideState second = bySide.get(sides.get(1));
    final String ahead =
        isAhead(first, second) ? sides.get(0) : isAhead(second, first) ? sides.get(1) : null;
    streak = ahead == null ? 0 : Objects.equals(ahead, leader) ? streak + 1 : 1;
    leader = ahead;
    return streak >= spec.rounds ? leader : null;
  }

  private boolean isAhead(final SideState a, final SideState b) {
    return a.tuv * 100L > b.tuv * (100L + spec.tuvLeadPercent)
        && a.production * 100L > b.production * (100L + spec.productionLeadPercent)
        && a.enemyCapitalsHeld > 0
        && a.capitalsLost == 0;
  }

  /**
   * Every side's state now: TUV of its units on the map and in its players' holdings, production of
   * the territories it owns, and capitals. A capital is lost when an owner not allied with the
   * player it is the capital of holds it; that owner's side then holds an enemy capital.
   */
  static Map<String, SideState> measure(final GameData data) {
    final TuvCostsCalculator costs = new TuvCostsCalculator();
    final Map<String, int[]> acc = new TreeMap<>(); // tuv, production, lost, held
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      final int[] a = acc.computeIfAbsent(AiMatch.allianceOf(p), k -> new int[4]);
      for (final Unit u : p.getUnitCollection()) {
        a[0] += costs.getCostsForTuv(u.getOwner()).getInt(u.getType());
      }
    }
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection()) {
        if (!u.getOwner().isNull()) {
          acc.get(AiMatch.allianceOf(u.getOwner()))[0] +=
              costs.getCostsForTuv(u.getOwner()).getInt(u.getType());
        }
      }
      final GamePlayer owner = t.getOwner();
      if (owner.isNull()) {
        continue;
      }
      acc.get(AiMatch.allianceOf(owner))[1] += TerritoryAttachment.getProduction(t);
      TerritoryAttachment.get(t)
          .flatMap(TerritoryAttachment::getCapital)
          .map(name -> data.getPlayerList().getPlayerId(name))
          .filter(capitalOf -> !capitalOf.equals(owner))
          .filter(capitalOf -> !data.getRelationshipTracker().isAllied(capitalOf, owner))
          .ifPresent(
              capitalOf -> {
                acc.get(AiMatch.allianceOf(capitalOf))[2]++;
                acc.get(AiMatch.allianceOf(owner))[3]++;
              });
    }
    final Map<String, SideState> result = new TreeMap<>();
    acc.forEach((side, a) -> result.put(side, new SideState(a[0], a[1], a[2], a[3])));
    return result;
  }
}
