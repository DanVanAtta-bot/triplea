package games.strategy.triplea.odds.calculator;

import static games.strategy.triplea.delegate.GameDataTestUtil.aaGun;
import static games.strategy.triplea.delegate.GameDataTestUtil.americans;
import static games.strategy.triplea.delegate.GameDataTestUtil.armour;
import static games.strategy.triplea.delegate.GameDataTestUtil.artillery;
import static games.strategy.triplea.delegate.GameDataTestUtil.battleship;
import static games.strategy.triplea.delegate.GameDataTestUtil.bomber;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.carrier;
import static games.strategy.triplea.delegate.GameDataTestUtil.destroyer;
import static games.strategy.triplea.delegate.GameDataTestUtil.fighter;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.japanese;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static games.strategy.triplea.delegate.GameDataTestUtil.submarine;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.GameDataTestUtil.transport;
import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.random.PlainRandomSource;
import games.strategy.triplea.delegate.TerritoryEffectHelper;
import games.strategy.triplea.odds.calculator.adapter.EngineRandomSource;
import games.strategy.triplea.odds.calculator.adapter.GameDataBattleAdapter;
import games.strategy.triplea.odds.calculator.context.model.BattleOptions;
import games.strategy.triplea.odds.calculator.context.model.BattleResult;
import games.strategy.triplea.odds.calculator.context.model.BattleScenario;
import games.strategy.triplea.odds.calculator.context.reference.ReferenceBattleSimulator;
import games.strategy.triplea.odds.calculator.context.vector.VectorizedHitRoller;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the bounded simulator's exact per-run results on a fixed set of LHTR battles under seeded
 * dice: land with artillery support and AA, a multi-HP naval battle with subs, carriers and
 * destroyers, an amphibious assault with shore bombardment, and a retreat-limited attack. A pure
 * performance change to the simulator must leave every digest unchanged; the digests were recorded
 * before the simulator's hot paths were optimized.
 */
class BoundedCalcGoldenResultsTest {
  private static final int RUNS = 1000;
  private static final long SEED = 20261026L;

  private static GameData data;

  @BeforeAll
  static void loadMap() {
    data = TestMapGameData.LHTR.getGameData();
  }

  @Test
  void landAttackWithSupportAndAa() throws Exception {
    final List<Unit> attacking = new ArrayList<>(infantry(data).create(6, russians(data)));
    attacking.addAll(artillery(data).create(3, russians(data)));
    attacking.addAll(armour(data).create(2, russians(data)));
    attacking.addAll(fighter(data).create(1, russians(data)));
    final List<Unit> defending = new ArrayList<>(infantry(data).create(5, germans(data)));
    defending.addAll(artillery(data).create(1, germans(data)));
    defending.addAll(armour(data).create(1, germans(data)));
    defending.addAll(aaGun(data).create(1, germans(data)));

    assertThat(digest(russians(data), germans(data), "Eastern Europe", attacking, defending))
        .isEqualTo("fca1f97bc6d1bd4b");
  }

  @Test
  void airHeavyAttack() throws Exception {
    final List<Unit> attacking = new ArrayList<>(infantry(data).create(4, germans(data)));
    attacking.addAll(artillery(data).create(2, germans(data)));
    attacking.addAll(armour(data).create(3, germans(data)));
    attacking.addAll(fighter(data).create(2, germans(data)));
    attacking.addAll(bomber(data).create(1, germans(data)));
    final List<Unit> defending = new ArrayList<>(infantry(data).create(8, russians(data)));
    defending.addAll(aaGun(data).create(1, russians(data)));
    defending.addAll(fighter(data).create(1, russians(data)));

    assertThat(digest(germans(data), russians(data), "Karelia S.S.R.", attacking, defending))
        .isEqualTo("04b09f22d65b2b8e");
  }

  @Test
  void navalBattleWithMultiHitShipsAndSubs() throws Exception {
    final List<Unit> attacking = new ArrayList<>(battleship(data).create(1, british(data)));
    attacking.addAll(destroyer(data).create(2, british(data)));
    attacking.addAll(carrier(data).create(1, british(data)));
    attacking.addAll(fighter(data).create(2, british(data)));
    final List<Unit> defending = new ArrayList<>(submarine(data).create(2, germans(data)));
    defending.addAll(battleship(data).create(1, germans(data)));
    defending.addAll(transport(data).create(1, germans(data)));

    assertThat(digest(british(data), germans(data), "5 Sea Zone", attacking, defending))
        .isEqualTo("be16d34eada352db");
  }

  @Test
  void subsAndBomberAgainstDestroyerAndTransports() throws Exception {
    final List<Unit> attacking = new ArrayList<>(submarine(data).create(3, japanese(data)));
    attacking.addAll(bomber(data).create(1, japanese(data)));
    final List<Unit> defending = new ArrayList<>(destroyer(data).create(1, americans(data)));
    defending.addAll(transport(data).create(2, americans(data)));

    assertThat(digest(japanese(data), americans(data), "7 Sea Zone", attacking, defending))
        .isEqualTo("2316aef814b1290c");
  }

  @Test
  void amphibiousAssaultWithBombard() throws Exception {
    final List<Unit> attacking = new ArrayList<>(infantry(data).create(4, americans(data)));
    attacking.addAll(armour(data).create(2, americans(data)));
    final List<Unit> bombarding = battleship(data).create(1, americans(data));
    final List<Unit> defending = new ArrayList<>(infantry(data).create(3, germans(data)));
    defending.addAll(artillery(data).create(1, germans(data)));
    defending.addAll(aaGun(data).create(1, germans(data)));
    final BattleOptions options =
        new BattleOptions(false, List.of(), List.of(), -1, -1, true, true);

    assertThat(
            digest(
                americans(data),
                germans(data),
                "Western Europe",
                attacking,
                defending,
                bombarding,
                options))
        .isEqualTo("64c0147620831130");
  }

  @Test
  void attackThatRetreatsAfterTwoRounds() throws Exception {
    final List<Unit> attacking = new ArrayList<>(infantry(data).create(5, germans(data)));
    attacking.addAll(armour(data).create(4, germans(data)));
    attacking.addAll(fighter(data).create(2, germans(data)));
    final List<Unit> defending = new ArrayList<>(infantry(data).create(9, russians(data)));
    defending.addAll(artillery(data).create(2, russians(data)));
    final BattleOptions options = new BattleOptions(true, List.of(), List.of(), 2, 3, false, false);

    assertThat(
            digest(
                germans(data),
                russians(data),
                "Caucasus",
                attacking,
                defending,
                List.of(),
                options))
        .isEqualTo("4c427d8dd83c0770");
  }

  private static String digest(
      final GamePlayer attacker,
      final GamePlayer defender,
      final String where,
      final List<Unit> attacking,
      final List<Unit> defending)
      throws Exception {
    return digest(
        attacker,
        defender,
        where,
        attacking,
        defending,
        List.of(),
        new BattleOptions(false, List.of(), List.of(), -1, -1, false, false));
  }

  private static String digest(
      final GamePlayer attacker,
      final GamePlayer defender,
      final String where,
      final List<Unit> attacking,
      final List<Unit> defending,
      final List<Unit> bombarding,
      final BattleOptions options)
      throws Exception {
    final Territory location = territory(where, data);
    final BattleScenario scenario =
        new GameDataBattleAdapter()
            .toScenario(
                attacker,
                defender,
                location,
                attacking,
                defending,
                bombarding,
                TerritoryEffectHelper.getEffects(location),
                options);
    final List<BattleResult> results =
        new ReferenceBattleSimulator(new VectorizedHitRoller())
            .simulate(scenario, RUNS, new EngineRandomSource(new PlainRandomSource(SEED)))
            .results();
    final MessageDigest sha = MessageDigest.getInstance("SHA-256");
    for (final BattleResult result : results) {
      sha.update(result.toString().getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(sha.digest()).substring(0, 16);
  }
}
