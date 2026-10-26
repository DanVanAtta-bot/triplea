package games.strategy.triplea.ai.kata;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A scripted turn, read from JSON. Each phase is optional: a missing phase is played by the Hard
 * AI. Unit counts are keyed by unit type name.
 *
 * <pre>{@code
 * {
 *   "purchase": {"infantry": 6, "armour": 2},
 *   "combatMoves": [{"from": "Germany", "to": "Poland", "units": {"armour": 2}}],
 *   "nonCombatMoves": [{"from": "Germany", "to": "Western Europe", "units": {"infantry": 2},
 *                       "route": ["Germany", "Western Europe"]}],
 *   "aiFinishesMoves": false,
 *   "place": [{"territory": "Germany", "units": {"infantry": 6}}]
 * }
 * }</pre>
 *
 * <p>A present move list replaces the AI's moves for that phase, unless {@code aiFinishesMoves} is
 * true, in which case the AI moves whatever it still wants to afterwards. Units the script doesn't
 * place are placed by the AI.
 */
public final class KataScript {
  @Nullable Map<String, Integer> purchase;
  @Nullable List<Move> combatMoves;
  @Nullable List<Move> nonCombatMoves;
  boolean aiFinishesMoves;
  @Nullable List<Placement> place;

  /** One move: {@code route} (every territory, start first) is optional when {@code to} is set. */
  static final class Move {
    String from;
    @Nullable String to;
    Map<String, Integer> units;
    @Nullable List<String> route;
  }

  static final class Placement {
    String territory;
    Map<String, Integer> units;
  }

  static KataScript read(final Path file) throws IOException {
    final KataScript script = new Gson().fromJson(Files.readString(file), KataScript.class);
    if (script == null) {
      throw new IllegalArgumentException("Empty script: " + file);
    }
    return script;
  }
}
