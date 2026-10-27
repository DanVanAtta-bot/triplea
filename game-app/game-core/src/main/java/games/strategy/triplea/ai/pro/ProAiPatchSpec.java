package games.strategy.triplea.ai.pro;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link ProAiPatch} with an optional scope: {@code NAME} applies to every player that has it,
 * {@code NAME@Axis} only to players in that alliance, {@code NAME@Germans+Japanese} only to those
 * players. A scope term matches a player's name or any alliance the player is in.
 */
public record ProAiPatchSpec(ProAiPatch patch, Set<String> scope) {
  public ProAiPatchSpec {
    scope = Set.copyOf(scope);
  }

  public static ProAiPatchSpec unscoped(final ProAiPatch patch) {
    return new ProAiPatchSpec(patch, Set.of());
  }

  /** One spec: {@code NAME} or {@code NAME@Term+Term}. */
  public static ProAiPatchSpec parse(final String spec) {
    final String[] parts = spec.trim().split("@", -1);
    if (parts.length > 2 || (parts.length == 2 && parts[1].isBlank())) {
      throw new IllegalArgumentException("Patch spec must be NAME or NAME@Scope+Scope: " + spec);
    }
    final ProAiPatch patch = ProAiPatch.valueOf(parts[0].trim());
    if (parts.length == 1) {
      return unscoped(patch);
    }
    final Set<String> scope = new LinkedHashSet<>();
    for (final String term : parts[1].split("\\+", -1)) {
      if (term.isBlank()) {
        throw new IllegalArgumentException("Empty scope term in patch spec: " + spec);
      }
      scope.add(term.trim());
    }
    return new ProAiPatchSpec(patch, scope);
  }

  /** A comma list of specs; blank or {@code none} is the empty list. Each patch at most once. */
  public static List<ProAiPatchSpec> parseList(final String value) {
    if (value.isBlank() || value.trim().equalsIgnoreCase("none")) {
      return List.of();
    }
    final List<ProAiPatchSpec> specs = new ArrayList<>();
    final Set<ProAiPatch> seen = new HashSet<>();
    for (final String spec : value.split(",")) {
      final ProAiPatchSpec parsed = parse(spec);
      if (!seen.add(parsed.patch())) {
        throw new IllegalArgumentException("Patch listed twice: " + parsed.patch());
      }
      specs.add(parsed);
    }
    return List.copyOf(specs);
  }

  /** The patches of these specs, scopes dropped. */
  public static Set<ProAiPatch> patches(final Collection<ProAiPatchSpec> specs) {
    final Set<ProAiPatch> patches = new HashSet<>();
    specs.forEach(spec -> patches.add(spec.patch()));
    return patches;
  }

  public boolean isScoped() {
    return !scope.isEmpty();
  }

  /** Whether the patch is on for this player, given the alliances it is in. */
  public boolean appliesTo(final String playerName, final Collection<String> alliances) {
    return scope.isEmpty()
        || scope.contains(playerName)
        || alliances.stream().anyMatch(scope::contains);
  }

  /** {@code NAME} or {@code NAME@Term+Term} (scope terms sorted), as parsed. */
  public String label() {
    if (scope.isEmpty()) {
      return patch.name();
    }
    return patch.name() + "@" + String.join("+", scope.stream().sorted().toList());
  }

  @Override
  public String toString() {
    return label();
  }

  /** Labels of these specs, sorted. */
  public static List<String> labels(final Collection<ProAiPatchSpec> specs) {
    return specs.stream().map(ProAiPatchSpec::label).sorted().toList();
  }
}
