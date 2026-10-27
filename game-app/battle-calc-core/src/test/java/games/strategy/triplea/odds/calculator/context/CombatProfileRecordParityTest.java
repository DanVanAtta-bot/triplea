package games.strategy.triplea.odds.calculator.context;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.triplea.odds.calculator.context.model.CombatFlag;
import games.strategy.triplea.odds.calculator.context.model.CombatProfile;
import games.strategy.triplea.odds.calculator.context.model.DamageState;
import games.strategy.triplea.odds.calculator.context.model.Domain;
import games.strategy.triplea.odds.calculator.context.model.SupportCategory;
import games.strategy.triplea.odds.calculator.context.model.UnitTypeId;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * CombatProfile was a record; it caches its hash now. Hash-ordered collections must iterate as they
 * did, so its hashCode, equals and toString must match a record with the same components.
 */
class CombatProfileRecordParityTest {

  /** The record CombatProfile used to be, component for component. */
  private record AsRecord(
      UnitTypeId type,
      int attack,
      int defense,
      int rolls,
      int maxRoundsAa,
      int maxAaAttacks,
      int hitPoints,
      Domain domain,
      DamageState damage,
      Set<SupportCategory> gives,
      Set<SupportCategory> receives,
      EnumSet<CombatFlag> flags,
      AsRecord next) {}

  private static final SupportCategory ARTILLERY = new SupportCategory("support:artillery/a");

  @Test
  void hashCodeMatchesTheRecordItReplaced() {
    final CombatProfile damaged = battleship(1, null);
    final CombatProfile fresh = battleship(0, damaged);
    final AsRecord damagedRecord = battleshipRecord(1, null);
    final AsRecord freshRecord = battleshipRecord(0, damagedRecord);

    assertThat(damaged.hashCode()).isEqualTo(damagedRecord.hashCode());
    assertThat(fresh.hashCode()).isEqualTo(freshRecord.hashCode());
    assertThat(infantry(4).hashCode()).isEqualTo(infantryRecord(4).hashCode());
  }

  @Test
  void toStringMatchesTheRecordItReplaced() {
    final CombatProfile fresh = battleship(0, battleship(1, null));
    final AsRecord freshRecord = battleshipRecord(0, battleshipRecord(1, null));

    assertThat(fresh.toString())
        .isEqualTo(freshRecord.toString().replace("AsRecord[", "CombatProfile["));
  }

  @Test
  void equalityIsByValue() {
    assertThat(infantry(1)).isEqualTo(infantry(1)).isNotEqualTo(infantry(2));
    assertThat(battleship(0, battleship(1, null)))
        .isEqualTo(battleship(0, battleship(1, null)))
        .isNotEqualTo(battleship(0, null));
  }

  private static CombatProfile infantry(final int attack) {
    return new CombatProfile(
        new UnitTypeId("infantry"),
        attack,
        2,
        1,
        -1,
        -1,
        1,
        Domain.LAND,
        new DamageState(0),
        Set.of(),
        Set.of(ARTILLERY),
        EnumSet.noneOf(CombatFlag.class),
        null);
  }

  private static AsRecord infantryRecord(final int attack) {
    return new AsRecord(
        new UnitTypeId("infantry"),
        attack,
        2,
        1,
        -1,
        -1,
        1,
        Domain.LAND,
        new DamageState(0),
        Set.of(),
        Set.of(ARTILLERY),
        EnumSet.noneOf(CombatFlag.class),
        null);
  }

  private static CombatProfile battleship(final int hits, final CombatProfile next) {
    return new CombatProfile(
        new UnitTypeId("battleship"),
        4,
        4,
        1,
        -1,
        -1,
        2 - hits,
        Domain.SEA,
        new DamageState(hits),
        Set.of(),
        Set.of(),
        EnumSet.of(CombatFlag.values()[0]),
        next);
  }

  private static AsRecord battleshipRecord(final int hits, final AsRecord next) {
    return new AsRecord(
        new UnitTypeId("battleship"),
        4,
        4,
        1,
        -1,
        -1,
        2 - hits,
        Domain.SEA,
        new DamageState(hits),
        Set.of(),
        Set.of(),
        EnumSet.of(CombatFlag.values()[0]),
        next);
  }
}
