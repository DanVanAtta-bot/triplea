package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProAiPatchSpecTest {

  @Test
  void plainNameIsUnscoped() {
    final ProAiPatchSpec spec = ProAiPatchSpec.parse("NO_OP");

    assertThat(spec.patch()).isEqualTo(ProAiPatch.NO_OP);
    assertThat(spec.isScoped()).isFalse();
    assertThat(spec.label()).isEqualTo("NO_OP");
    assertThat(spec.appliesTo("Russians", List.of("Allies"))).isTrue();
  }

  @Test
  void allianceScopeMatchesPlayersInThatAlliance() {
    final ProAiPatchSpec spec = ProAiPatchSpec.parse("NO_OP@Axis");

    assertThat(spec.appliesTo("Germans", List.of("Axis"))).isTrue();
    assertThat(spec.appliesTo("Russians", List.of("Allies"))).isFalse();
  }

  @Test
  void playerScopeMatchesThosePlayersOnly() {
    final ProAiPatchSpec spec = ProAiPatchSpec.parse("NO_OP@Japanese+Germans");

    assertThat(spec.appliesTo("Germans", List.of("Axis"))).isTrue();
    assertThat(spec.appliesTo("Italians", List.of("Axis"))).isFalse();
    assertThat(spec.label()).isEqualTo("NO_OP@Germans+Japanese");
  }

  @Test
  void parseListAcceptsNoneAndMixedSpecs() {
    assertThat(ProAiPatchSpec.parseList("none")).isEmpty();
    assertThat(ProAiPatchSpec.parseList("NO_OP@Axis, CAN_HOLD_STRICT"))
        .extracting(ProAiPatchSpec::label)
        .containsExactly("NO_OP@Axis", "CAN_HOLD_STRICT");
  }

  @Test
  void parseRejectsBadSpecs() {
    assertThrows(IllegalArgumentException.class, () -> ProAiPatchSpec.parse("NOT_A_PATCH"));
    assertThrows(IllegalArgumentException.class, () -> ProAiPatchSpec.parse("NO_OP@"));
    assertThrows(IllegalArgumentException.class, () -> ProAiPatchSpec.parse("NO_OP@Axis+"));
    assertThrows(IllegalArgumentException.class, () -> ProAiPatchSpec.parse("NO_OP@Axis@Allies"));
    assertThrows(
        IllegalArgumentException.class, () -> ProAiPatchSpec.parseList("NO_OP,NO_OP@Axis"));
  }

  @Test
  void proDataTurnsScopedPatchOnOnlyForPlayersInScope() {
    final GameData data = TestMapGameData.REVISED.getGameData();
    final List<ProAiPatchSpec> specs = ProAiPatchSpec.parseList("NO_OP@Axis,CAN_HOLD_STRICT");
    final ProData germans = ProData.withPatchSpecs(specs);
    final ProData russians = ProData.withPatchSpecs(specs);

    germans.initializeSimulation(
        new ProAi("Germans", "test"), data, data.getPlayerList().getPlayerId("Germans"));
    russians.initializeSimulation(
        new ProAi("Russians", "test"), data, data.getPlayerList().getPlayerId("Russians"));

    assertThat(germans.getPatches())
        .containsExactlyInAnyOrder(ProAiPatch.NO_OP, ProAiPatch.CAN_HOLD_STRICT);
    assertThat(russians.getPatches()).isEqualTo(Set.of(ProAiPatch.CAN_HOLD_STRICT));
    assertThat(russians.isPatchOn(ProAiPatch.NO_OP)).isFalse();
  }

  @Test
  void patchHitsByLabelKeepTheScope() {
    final GameData data = TestMapGameData.REVISED.getGameData();
    final ProData germans =
        ProData.withPatchSpecs(ProAiPatchSpec.parseList("NO_OP@Axis,CAN_HOLD_STRICT"));
    germans.initializeSimulation(
        new ProAi("Germans", "test"), data, data.getPlayerList().getPlayerId("Germans"));

    germans.isPatchOn(ProAiPatch.NO_OP);
    germans.isPatchOn(ProAiPatch.NO_OP);
    germans.isPatchOn(ProAiPatch.CAN_HOLD_STRICT);

    assertThat(germans.getPatchHitsByLabel())
        .isEqualTo(Map.of("NO_OP@Axis", 2, "CAN_HOLD_STRICT", 1));
    assertThat(germans.getPatchHits()).containsEntry(ProAiPatch.NO_OP, 2);
  }

  @Test
  void scopedPatchIsOffBeforeThePlayerIsKnown() {
    final ProData proData =
        ProData.withPatchSpecs(ProAiPatchSpec.parseList("NO_OP@Axis,CAN_HOLD_STRICT"));

    assertThat(proData.getPatches()).isEqualTo(Set.of(ProAiPatch.CAN_HOLD_STRICT));
  }
}
