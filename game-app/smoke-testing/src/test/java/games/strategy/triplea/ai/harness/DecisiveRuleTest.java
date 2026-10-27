package games.strategy.triplea.ai.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import games.strategy.triplea.ai.harness.DecisiveRule.SideState;
import games.strategy.triplea.ai.harness.DecisiveRule.Spec;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisiveRuleTest {

  private static final SideState WINNING_AXIS = new SideState(1200, 110, 0, 1);
  private static final SideState LOSING_ALLIES = new SideState(1000, 60, 1, 0);

  @Test
  void callsTheLeaderAfterTheGivenNumberOfConsecutiveRoundEnds() {
    final DecisiveRule rule = new DecisiveRule(Spec.parse("2:10:0"));

    assertThat(rule.roundEnded(Map.of("Axis", WINNING_AXIS, "Allies", LOSING_ALLIES))).isNull();
    assertThat(rule.roundEnded(Map.of("Axis", WINNING_AXIS, "Allies", LOSING_ALLIES)))
        .isEqualTo("Axis");
  }

  @Test
  void aRoundEndWithoutALeaderRestartsTheCount() {
    final DecisiveRule rule = new DecisiveRule(Spec.parse("2:10:0"));
    final SideState evenAllies = new SideState(1200, 110, 0, 0);

    rule.roundEnded(Map.of("Axis", WINNING_AXIS, "Allies", LOSING_ALLIES));
    rule.roundEnded(Map.of("Axis", WINNING_AXIS, "Allies", evenAllies));

    assertThat(rule.roundEnded(Map.of("Axis", WINNING_AXIS, "Allies", LOSING_ALLIES))).isNull();
  }

  @Test
  void aTuvLeadAtTheThresholdIsNotEnough() {
    final DecisiveRule rule = new DecisiveRule(Spec.parse("1:20:0"));

    assertThat(rule.roundEnded(Map.of("Axis", WINNING_AXIS, "Allies", LOSING_ALLIES))).isNull();
  }

  @Test
  void aLeaderMissingOneOfItsOwnCapitalsIsNotAhead() {
    final DecisiveRule rule = new DecisiveRule(Spec.parse("1:0:0"));
    final SideState axisLostACapital = new SideState(1200, 110, 1, 1);

    assertThat(rule.roundEnded(Map.of("Axis", axisLostACapital, "Allies", LOSING_ALLIES))).isNull();
  }

  @Test
  void aLeaderHoldingNoEnemyCapitalIsNotAhead() {
    final DecisiveRule rule = new DecisiveRule(Spec.parse("1:0:0"));
    final SideState axisHoldsNoCapital = new SideState(1200, 110, 0, 0);
    final SideState alliesWithAllCapitals = new SideState(1000, 60, 0, 0);

    assertThat(rule.roundEnded(Map.of("Axis", axisHoldsNoCapital, "Allies", alliesWithAllCapitals)))
        .isNull();
  }

  @Test
  void specNeedsThreeNonNegativeFields() {
    assertThatThrownBy(() -> Spec.parse("2:10")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Spec.parse("0:10:0")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Spec.parse("2:-1:0")).isInstanceOf(IllegalArgumentException.class);
  }
}
