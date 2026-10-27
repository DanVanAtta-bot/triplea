package games.strategy.triplea.odds.calculator.context.model;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The board-counter identity: the pure combat stats a unit carries into battle, and the merge key
 * for the state vector — equality means combat-fungible. A property lives here iff it changes
 * combat behavior, so damage is in but lifecycle is not.
 *
 * <p>A value class with record semantics (component accessors, equality, {@code hashCode} and
 * {@code toString} all exactly as the record it replaced) whose hash is computed once: the
 * simulator keys hash maps by profiles every round of every run, and recomputing the hash walked
 * the support sets, the flag set and the whole {@code next} chain each time. The hash is the same
 * value the record produced, so hash-ordered collections iterate exactly as before.
 */
public final class CombatProfile {
  private final UnitTypeId type;
  private final int attack;
  private final int defense;
  private final int rolls;
  // The last round through which an IS_AA unit fires ('maxRoundsAa'; -1 = every round), mirroring
  // the engine's Matches.unitIsAaThatCanFireOnRound gate — a standard gun fires round 1 only.
  // Read only for AA profiles; baked -1 for every non-AA unit, which never consults it.
  private final int maxRoundsAa;
  // The raw per-gun AA dice cap ('getMaxAaAttacks'; -1 = infinite, else the finite per-gun roll
  // count). Static per unit-type, so identical guns still merge on this component; the per-round
  // total-dice cap against the live air-target count is applied at fire time, mirroring the
  // engine's AaPowerStrengthAndRolls. Non-AA units bake a constant -1, so no fungible profiles
  // split on it.
  private final int maxAaAttacks;
  private final int hitPoints;
  private final Domain domain;
  private final DamageState damage;
  // The support categories this unit emits and consumes; empty means neither. Sets so a unit can
  // carry several, each matched independently against a rule; value-based Set equality keeps them
  // in the merge key without depending on order.
  private final Set<SupportCategory> gives;
  private final Set<SupportCategory> receives;
  // Effectively immutable: populated once at construction and only read thereafter, so the
  // profile
  // is safe to share across the simulator threads that read one scenario.
  private final EnumSet<CombatFlag> flags;
  // The profile this one migrates to on a hit, or null if the hit kills it; encodes
  // whenHitPointsDamagedChangesInto (a stat or unit-type change) as data. Combat-relevant, so it
  // participates in equality (invariant 1) — a same-stats unit with a different successor is a
  // different bucket.
  private final CombatProfile next;
  private final int hash;

  public CombatProfile(
      final UnitTypeId type,
      final int attack,
      final int defense,
      final int rolls,
      final int maxRoundsAa,
      final int maxAaAttacks,
      final int hitPoints,
      final Domain domain,
      final DamageState damage,
      final Set<SupportCategory> gives,
      final Set<SupportCategory> receives,
      final EnumSet<CombatFlag> flags,
      final CombatProfile next) {
    this.type = type;
    this.attack = attack;
    this.defense = defense;
    this.rolls = rolls;
    this.maxRoundsAa = maxRoundsAa;
    this.maxAaAttacks = maxAaAttacks;
    this.hitPoints = hitPoints;
    this.domain = domain;
    this.damage = damage;
    this.gives = gives;
    this.receives = receives;
    this.flags = flags;
    this.next = next;
    // The record hash: 31 * h + component hash, in declaration order, starting from 0.
    int h = 0;
    h = 31 * h + Objects.hashCode(type);
    h = 31 * h + Integer.hashCode(attack);
    h = 31 * h + Integer.hashCode(defense);
    h = 31 * h + Integer.hashCode(rolls);
    h = 31 * h + Integer.hashCode(maxRoundsAa);
    h = 31 * h + Integer.hashCode(maxAaAttacks);
    h = 31 * h + Integer.hashCode(hitPoints);
    h = 31 * h + Objects.hashCode(domain);
    h = 31 * h + Objects.hashCode(damage);
    h = 31 * h + Objects.hashCode(gives);
    h = 31 * h + Objects.hashCode(receives);
    h = 31 * h + Objects.hashCode(flags);
    h = 31 * h + Objects.hashCode(next);
    this.hash = h;
  }

  public UnitTypeId type() {
    return type;
  }

  public int attack() {
    return attack;
  }

  public int defense() {
    return defense;
  }

  public int rolls() {
    return rolls;
  }

  public int maxRoundsAa() {
    return maxRoundsAa;
  }

  public int maxAaAttacks() {
    return maxAaAttacks;
  }

  public int hitPoints() {
    return hitPoints;
  }

  public Domain domain() {
    return domain;
  }

  public DamageState damage() {
    return damage;
  }

  public Set<SupportCategory> gives() {
    return gives;
  }

  public Set<SupportCategory> receives() {
    return receives;
  }

  public EnumSet<CombatFlag> flags() {
    return flags;
  }

  public CombatProfile next() {
    return next;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CombatProfile other) || hash != other.hash) {
      return false;
    }
    return Objects.equals(type, other.type)
        && attack == other.attack
        && defense == other.defense
        && rolls == other.rolls
        && maxRoundsAa == other.maxRoundsAa
        && maxAaAttacks == other.maxAaAttacks
        && hitPoints == other.hitPoints
        && Objects.equals(domain, other.domain)
        && Objects.equals(damage, other.damage)
        && Objects.equals(gives, other.gives)
        && Objects.equals(receives, other.receives)
        && Objects.equals(flags, other.flags)
        && Objects.equals(next, other.next);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public String toString() {
    return "CombatProfile[type="
        + type
        + ", attack="
        + attack
        + ", defense="
        + defense
        + ", rolls="
        + rolls
        + ", maxRoundsAa="
        + maxRoundsAa
        + ", maxAaAttacks="
        + maxAaAttacks
        + ", hitPoints="
        + hitPoints
        + ", domain="
        + domain
        + ", damage="
        + damage
        + ", gives="
        + gives
        + ", receives="
        + receives
        + ", flags="
        + flags
        + ", next="
        + next
        + "]";
  }

  /** The profile a unit becomes after one hit, or empty when the hit kills it. */
  public Optional<CombatProfile> onHit() {
    return Optional.ofNullable(next);
  }
}
