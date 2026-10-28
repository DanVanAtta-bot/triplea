package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import lombok.experimental.UtilityClass;

/** Casualty choices for ProAI that look past unit cost. */
@UtilityClass
public class ProCasualtyUtils {

  /** Keep {@code landToKeep} alive by losing {@code airToLose} instead. */
  public record KeepLandSwap(Unit landToKeep, Unit airToLose) {
    public List<Unit> apply(final Collection<Unit> killed) {
      final List<Unit> swapped = new ArrayList<>(killed);
      swapped.remove(landToKeep);
      swapped.add(airToLose);
      return swapped;
    }
  }

  /**
   * For an attacker on land: if {@code killed} takes every land unit still alive while air
   * survives, the swap that keeps the most valuable of those land units by losing the cheapest
   * surviving air unit that these hits may take. Only land units capture, so without it a won
   * battle leaves the territory to the defender.
   *
   * @param attackersAlive the attacker's units in the battle not yet marked as casualties,
   *     including {@code killed}
   * @param selectFrom the units these hits may take
   * @param damaged units these hits damage instead of kill; never also killed
   */
  public static Optional<KeepLandSwap> findKeepLandSwap(
      final Collection<Unit> killed,
      final Collection<Unit> attackersAlive,
      final Collection<Unit> selectFrom,
      final Collection<Unit> damaged,
      final ToDoubleFunction<Unit> value) {
    final List<Unit> survivors = new ArrayList<>(attackersAlive);
    survivors.removeAll(killed);
    if (survivors.stream().anyMatch(Matches.unitIsLand())) {
      return Optional.empty();
    }
    final Optional<Unit> landToKeep =
        killed.stream()
            .filter(Matches.unitIsLand())
            .filter(attackersAlive::contains)
            .max(Comparator.comparingDouble(value));
    final Optional<Unit> airToLose =
        survivors.stream()
            .filter(Matches.unitIsAir())
            .filter(selectFrom::contains)
            .filter(u -> !damaged.contains(u))
            .min(Comparator.comparingDouble(value));
    if (landToKeep.isEmpty() || airToLose.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new KeepLandSwap(landToKeep.get(), airToLose.get()));
  }
}
