package games.strategy.triplea.ai.harness;

import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ai.pro.ProDecisionListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects ProAI attack plans from the AI threads. The recorder drains it between game steps, when
 * no AI is running.
 */
final class DecisionLog implements ProDecisionListener {

  record Plan(String player, int round, boolean simulated, List<AttackDecision> decisions) {}

  private final List<Plan> pending = new ArrayList<>();

  @Override
  public synchronized void combatMovePlanned(
      final GamePlayer player,
      final int round,
      final boolean simulated,
      final List<AttackDecision> decisions) {
    pending.add(new Plan(player.getName(), round, simulated, List.copyOf(decisions)));
  }

  synchronized List<Plan> drain() {
    final List<Plan> plans = List.copyOf(pending);
    pending.clear();
    return plans;
  }
}
