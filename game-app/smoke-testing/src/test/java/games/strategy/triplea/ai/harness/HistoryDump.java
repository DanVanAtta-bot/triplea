package games.strategy.triplea.ai.harness;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.history.Event;
import games.strategy.engine.history.EventChild;
import games.strategy.engine.history.HistoryNode;
import games.strategy.triplea.delegate.DiceRoll;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Writes a game's whole history (every step, move, purchase, battle and dice roll) and its final
 * board as plain text, with no unit ids, so two seeded runs can be diffed move by move.
 */
final class HistoryDump {
  private HistoryDump() {}

  static void write(final GameData data, final Path file) {
    final List<String> lines = new ArrayList<>();
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      final Enumeration<?> en = ((HistoryNode) data.getHistory().getRoot()).preorderEnumeration();
      while (en.hasMoreElements()) {
        final HistoryNode node = (HistoryNode) en.nextElement();
        final String indent = "  ".repeat(Math.max(0, node.getLevel()));
        final Object rendering =
            node instanceof Event e
                ? e.getRenderingData()
                : node instanceof EventChild c ? c.getRenderingData() : null;
        lines.add(
            indent
                + node.getClass().getSimpleName()
                + ": "
                + node.getTitle()
                + (rendering == null ? "" : " | " + render(rendering)));
      }
      lines.add("FINAL BOARD");
      for (final Territory t : data.getMap().getTerritories()) {
        final Map<String, Long> units =
            t.getUnits().stream()
                .collect(
                    Collectors.groupingBy(
                        HistoryDump::unitKey, TreeMap::new, Collectors.counting()));
        lines.add(t.getName() + " owner=" + t.getOwner().getName() + " units=" + units);
      }
    }
    try {
      Files.write(file, lines);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String render(final Object o) {
    if (o instanceof DiceRoll || o instanceof String || o instanceof Number) {
      return o.toString();
    }
    if (o instanceof Unit u) {
      return unitKey(u);
    }
    if (o instanceof Collection<?> c) {
      return c.stream().map(HistoryDump::render).sorted().toList().toString();
    }
    if (o instanceof Object[] a) {
      return Arrays.stream(a).map(HistoryDump::render).toList().toString();
    }
    return o.getClass().getSimpleName();
  }

  private static String unitKey(final Unit u) {
    return u.getOwner().getName() + ":" + u.getType().getName() + ":h" + u.getHits();
  }
}
