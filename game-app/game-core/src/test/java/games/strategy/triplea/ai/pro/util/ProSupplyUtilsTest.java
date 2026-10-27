package games.strategy.triplea.ai.pro.util;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProSupplyUtilsTest {
  private final GameData data = TestMapGameData.LHTR.getGameData();

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrNull(name);
  }

  @Test
  void arrivalGrowsToTheFeedingFactoriesSteadyRate() {
    final ProSupplyUtils.Arrival arrival =
        ProSupplyUtils.arrival(
            data, List.of(player("Germans")), territory("Russia"), true, true, 8, Set.of(), null);

    for (int t = 1; t <= 8; t++) {
      assertThat(arrival.at(t)).isGreaterThanOrEqualTo(arrival.at(t - 1));
    }
    // 40 PUs of infantry at 1 strength point per PU, the last from Germany 4 steps away
    assertThat(arrival.steadyRate()).isEqualTo(40.0);
    assertThat(arrival.horizon()).isEqualTo(4);
    assertThat(arrival.at(8) - arrival.at(7)).isEqualTo(40.0);
  }

  @Test
  void islandFactoryFeedsThroughATransportShuttle() {
    final ProSupplyUtils.Shuttle shuttle =
        ProSupplyUtils.shuttle(
            data,
            player("British"),
            territory("United Kingdom"),
            ProSupplyUtils.landDistances(data.getMap(), territory("Western Europe")));

    assertThat(shuttle).isNotNull();
    assertThat(shuttle.march()).isZero();
    assertThat(shuttle.unitsPerTurn()).isPositive();
  }

  @Test
  void noShuttleWithoutTransports() {
    assertThat(
            ProSupplyUtils.shuttle(
                data,
                player("Russians"),
                territory("Caucasus"),
                ProSupplyUtils.landDistances(data.getMap(), territory("Southern Europe"))))
        .isNull();
  }

  @Test
  void aNewFactoryNearerTheTargetRaisesEarlyArrival() {
    final GamePlayer germans = player("Germans");
    final Territory russia = territory("Russia");
    final ProSupplyUtils.Arrival base =
        ProSupplyUtils.arrival(data, List.of(germans), russia, true, true, 6, Set.of(), null);
    final ProSupplyUtils.Arrival withFactory =
        ProSupplyUtils.arrival(
            data, List.of(germans), russia, true, true, 6, Set.of(), territory("Eastern Europe"));

    assertThat(withFactory.at(3)).isGreaterThan(base.at(3));
    assertThat(withFactory.horizon()).isLessThanOrEqualTo(base.horizon());
  }

  @Test
  void islandFactoriesShareTheirTransports() {
    final GamePlayer british = player("British");
    final Territory germany = territory("Germany");
    final ProSupplyUtils.Arrival base =
        ProSupplyUtils.arrival(data, List.of(british), germany, true, true, 6, Set.of(), null);
    final ProSupplyUtils.Arrival withCanada =
        ProSupplyUtils.arrival(
            data, List.of(british), germany, true, true, 6, Set.of(), territory("Eastern Canada"));

    // A second island factory ships on the same transports, so nothing more arrives
    assertThat(withCanada.at(6)).isLessThanOrEqualTo(base.at(6));
  }
}
