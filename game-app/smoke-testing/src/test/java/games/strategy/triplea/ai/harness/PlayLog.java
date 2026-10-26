package games.strategy.triplea.ai.harness;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.history.Event;
import games.strategy.engine.history.EventChild;
import games.strategy.engine.history.History;
import games.strategy.engine.history.HistoryNode;
import games.strategy.engine.history.Round;
import games.strategy.engine.history.Step;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.data.PlacementDescription;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes {@code play.jsonl} for a game started from a human position: a {@code turn} record (TUV,
 * territory production and capitals per side, PUs per player) at every player change, and, at the
 * end, the history of the first turns in the forum-export schema ({@code position} at each turn
 * start, {@code event} with kind/desc/data/children/delta), so human and ProAI turns go through one
 * parser.
 */
final class PlayLog implements AutoCloseable {
  private static final Gson GSON =
      new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

  private final GameData data;
  private final BufferedWriter out;
  private final TuvCostsCalculator costs = new TuvCostsCalculator();
  private final HistoryNode startNode;
  private final int startRound;
  private String turnPlayer;
  private int turns;

  PlayLog(final GameData data, final Path file) throws IOException {
    this.data = data;
    Files.createDirectories(file.toAbsolutePath().getParent());
    this.out = Files.newBufferedWriter(file);
    this.startNode = data.getHistory().getLastNode();
    this.startRound = data.getSequence().getRound();
  }

  void line(final JsonObject o) {
    try {
      out.write(GSON.toJson(o));
      out.write('\n');
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Player turns started so far, the first one included. */
  int turns() {
    return turns;
  }

  /** Call before each step; writes a turn record when the step's player differs from the last. */
  void beforeStep() {
    final GamePlayer p = data.getSequence().getStep().getPlayerId();
    // Steps past their run limit (round-1 bids) are skipped by the engine, not played.
    if (p == null
        || p.getName().equals(turnPlayer)
        || data.getSequence().getStep().hasReachedMaxRunCount()) {
      return;
    }
    turnPlayer = p.getName();
    turns++;
    final JsonObject o = summary("turn");
    o.addProperty("player", turnPlayer);
    line(o);
  }

  JsonObject summary(final String type) {
    final JsonObject o = new JsonObject();
    o.addProperty("t", type);
    o.addProperty("round", data.getSequence().getRound());
    o.addProperty("step", data.getSequence().getStep().getName());
    final Map<String, Integer> tuv = new TreeMap<>();
    final Map<String, Integer> production = new TreeMap<>();
    final Map<String, Integer> vc = new TreeMap<>();
    final Map<String, List<String>> capitalsLost = new TreeMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection().getUnits()) {
        tuv.merge(
            AiMatch.allianceOf(u.getOwner()),
            costs.getCostsForTuv(u.getOwner()).getInt(u.getType()),
            Integer::sum);
      }
      if (t.getOwner().isNull()) {
        continue;
      }
      final String side = AiMatch.allianceOf(t.getOwner());
      production.merge(side, TerritoryAttachment.getProduction(t), Integer::sum);
      TerritoryAttachment.get(t)
          .ifPresent(
              ta -> {
                if (ta.getVictoryCity() > 0) {
                  vc.merge(side, ta.getVictoryCity(), Integer::sum);
                }
                ta.getCapital()
                    .map(c -> data.getPlayerList().getPlayerId(c))
                    .filter(c -> c != null && !c.equals(t.getOwner()))
                    .filter(c -> !data.getRelationshipTracker().isAllied(c, t.getOwner()))
                    .ifPresent(
                        c ->
                            capitalsLost
                                .computeIfAbsent(c.getName(), k -> new ArrayList<>())
                                .add(t.getName()));
              });
    }
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final Map<String, Integer> pusByPlayer = new TreeMap<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      pusByPlayer.put(p.getName(), p.getResources().getQuantity(pus));
    }
    o.add("tuv", GSON.toJsonTree(tuv));
    o.add("production", GSON.toJsonTree(production));
    o.add("vc", GSON.toJsonTree(vc));
    o.add("pus", GSON.toJsonTree(pusByPlayer));
    o.add("capitals_lost", GSON.toJsonTree(capitalsLost));
    return o;
  }

  /**
   * Appends the history since the start in the forum-export schema, for the first {@code maxTurns}
   * player turns. Seeks the history, so call it only once play is over.
   */
  void exportHistory(final int maxTurns) {
    if (maxTurns <= 0) {
      return;
    }
    final History history = data.getHistory();
    history.enableSeeking(null);
    history.gotoNode(startNode);
    TreeMap<String, Snap> before = snapshot();
    int round = startRound;
    String stepName = null;
    String stepPlayer = null;
    String player = null;
    int turnsSeen = 0;
    boolean after = false;
    final Enumeration<?> en = ((HistoryNode) history.getRoot()).preorderEnumeration();
    while (en.hasMoreElements()) {
      final HistoryNode n = (HistoryNode) en.nextElement();
      if (!after) {
        // A rebuilt position has no Round node for its starting round.
        after = n == startNode;
        continue;
      }
      if (n instanceof Round r) {
        round = r.getRoundNo();
      }
      if (n instanceof Step s) {
        stepName = s.getStepName();
        stepPlayer = s.getPlayerId().map(GamePlayer::getName).orElse(null);
        if (stepPlayer != null && !stepPlayer.equals(player)) {
          player = stepPlayer;
          if (++turnsSeen > maxTurns) {
            break;
          }
          final Object prev = n.getPreviousLeaf();
          history.gotoNode(prev == null ? (HistoryNode) history.getRoot() : (HistoryNode) prev);
          before = snapshot();
          final JsonObject pos = new JsonObject();
          pos.addProperty("t", "position");
          pos.addProperty("round", round);
          pos.addProperty("phase", "turn_start");
          pos.addProperty("player", player);
          pos.add("territories", position(before));
          line(pos);
        }
        final JsonObject st = new JsonObject();
        st.addProperty("t", "step");
        st.addProperty("round", round);
        st.addProperty("step", stepName);
        st.addProperty("player", stepPlayer);
        line(st);
      } else if (n instanceof Event e) {
        history.gotoNode(e);
        final TreeMap<String, Snap> afterSnap = snapshot();
        final JsonObject ev = new JsonObject();
        ev.addProperty("t", "event");
        ev.addProperty("round", round);
        ev.addProperty("step", stepName);
        ev.addProperty("player", stepPlayer);
        ev.addProperty("kind", kind(e, stepName));
        ev.addProperty("desc", e.getDescription());
        if (e.getRenderingData() != null) {
          ev.add("data", render(e.getRenderingData()));
        }
        if (e.getChildCount() > 0) {
          final JsonArray children = new JsonArray();
          for (int i = 0; i < e.getChildCount(); i++) {
            final EventChild c = (EventChild) e.getChildAt(i);
            final JsonObject cj = new JsonObject();
            cj.addProperty("title", c.getTitle());
            if (c.getRenderingData() != null) {
              cj.add("data", render(c.getRenderingData()));
            }
            children.add(cj);
          }
          ev.add("children", children);
        }
        ev.add("delta", delta(before, afterSnap));
        line(ev);
        before = afterSnap;
      }
    }
    history.gotoNode(history.getLastNode());
  }

  private static String kind(final Event e, final String stepName) {
    final Object rd = e.getRenderingData();
    final String d = e.getDescription() == null ? "" : e.getDescription();
    final String s = stepName == null ? "" : stepName.toLowerCase();
    if (rd instanceof MoveDescription) {
      return "move";
    }
    if (rd instanceof PlacementDescription) {
      return "place";
    }
    if (s.contains("purchase") || s.contains("bid")) {
      return "purchase";
    }
    if (d.startsWith("Battle in") || d.contains("battle in") || s.contains("battle")) {
      return "battle";
    }
    if (s.contains("tech")) {
      return "tech";
    }
    if (s.contains("endturn") || s.contains("endround")) {
      return "end_turn";
    }
    return "other";
  }

  private static final class Snap {
    String owner;
    final TreeMap<String, Integer> counts = new TreeMap<>();
    JsonArray cargo;
  }

  private TreeMap<String, Snap> snapshot() {
    final TreeMap<String, Snap> m = new TreeMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      final Snap s = new Snap();
      s.owner = t.getOwner() == null ? null : t.getOwner().getName();
      for (final Unit u : t.getUnitCollection().getUnits()) {
        s.counts.merge(
            u.getOwner().getName()
                + "|"
                + u.getType().getName()
                + (u.getHits() > 0 ? "|hit" + u.getHits() : ""),
            1,
            Integer::sum);
      }
      s.cargo = cargo(t.getUnitCollection().getUnits());
      m.put(t.getName(), s);
    }
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      final Snap s = new Snap();
      for (final Unit u : p.getUnitCollection().getUnits()) {
        s.counts.merge(p.getName() + "|" + u.getType().getName(), 1, Integer::sum);
      }
      final var resources = p.getResources().getResourcesCopy();
      for (final Resource r : resources.keySet()) {
        s.counts.merge("res|" + r.getName(), resources.getInt(r), Integer::sum);
      }
      m.put("@" + p.getName(), s);
    }
    return m;
  }

  private static JsonObject position(final TreeMap<String, Snap> snap) {
    final JsonObject terrs = new JsonObject();
    snap.forEach(
        (name, s) -> {
          final JsonObject t = new JsonObject();
          t.add("o", s.owner == null ? JsonNull.INSTANCE : GSON.toJsonTree(s.owner));
          t.add("u", GSON.toJsonTree(s.counts));
          if (s.cargo != null) {
            t.add("cargo", s.cargo);
          }
          terrs.add(name, t);
        });
    return terrs;
  }

  private static JsonArray cargo(final Collection<Unit> units) {
    final Map<Unit, TreeMap<String, Integer>> byTransport = new java.util.LinkedHashMap<>();
    for (final Unit u : units) {
      final Unit by = u.getTransportedBy();
      if (by != null) {
        byTransport
            .computeIfAbsent(by, k -> new TreeMap<>())
            .merge(u.getOwner().getName() + "|" + u.getType().getName(), 1, Integer::sum);
      }
    }
    if (byTransport.isEmpty()) {
      return null;
    }
    final JsonArray a = new JsonArray();
    byTransport.forEach(
        (tr, cargo) -> {
          final JsonObject c = new JsonObject();
          c.addProperty("by", tr.getOwner().getName() + "|" + tr.getType().getName());
          c.add("u", GSON.toJsonTree(cargo));
          a.add(c);
        });
    return a;
  }

  private static JsonObject delta(final TreeMap<String, Snap> a, final TreeMap<String, Snap> b) {
    final JsonObject d = new JsonObject();
    for (final Map.Entry<String, Snap> e : b.entrySet()) {
      final Snap x = a.getOrDefault(e.getKey(), new Snap());
      final Snap y = e.getValue();
      final TreeMap<String, Integer> diff = new TreeMap<>();
      final TreeSet<String> keys = new TreeSet<>(x.counts.keySet());
      keys.addAll(y.counts.keySet());
      for (final String u : keys) {
        final int n = y.counts.getOrDefault(u, 0) - x.counts.getOrDefault(u, 0);
        if (n != 0) {
          diff.put(u, n);
        }
      }
      final boolean ownerChanged = x.owner == null ? y.owner != null : !x.owner.equals(y.owner);
      if (diff.isEmpty() && !ownerChanged) {
        continue;
      }
      final JsonObject t = new JsonObject();
      if (ownerChanged) {
        final JsonArray o = new JsonArray();
        o.add(x.owner);
        o.add(y.owner);
        t.add("owner", o);
      }
      if (!diff.isEmpty()) {
        t.add("u", GSON.toJsonTree(diff));
      }
      d.add(e.getKey(), t);
    }
    return d;
  }

  private static JsonObject unitCounts(final Collection<Unit> units) {
    final TreeMap<String, Integer> m = new TreeMap<>();
    for (final Unit u : units) {
      m.merge(u.getOwner().getName() + "|" + u.getType().getName(), 1, Integer::sum);
    }
    return GSON.toJsonTree(m).getAsJsonObject();
  }

  private static JsonElement render(final Object rd) {
    final JsonObject o = new JsonObject();
    if (rd instanceof MoveDescription md) {
      final JsonArray route = new JsonArray();
      route.add(md.getRoute().getStart().getName());
      md.getRoute().getSteps().forEach(t -> route.add(t.getName()));
      o.addProperty("type", "move");
      o.add("units", unitCounts(md.getUnits()));
      o.add("route", route);
      return o;
    }
    if (rd instanceof PlacementDescription pd) {
      o.addProperty("type", "place");
      o.addProperty("territory", pd.getTerritory().getName());
      o.add("units", unitCounts(pd.getUnits()));
      return o;
    }
    if (rd instanceof Collection<?> c
        && !c.isEmpty()
        && c.stream().allMatch(Unit.class::isInstance)) {
      o.addProperty("type", "units");
      o.add("units", unitCounts(c.stream().map(Unit.class::cast).toList()));
      return o;
    }
    if (rd instanceof Territory t) {
      o.addProperty("type", "territory");
      o.addProperty("name", t.getName());
      return o;
    }
    if (rd instanceof Object[] arr) {
      final JsonArray a = new JsonArray();
      for (final Object x : arr) {
        a.add(x == null ? JsonNull.INSTANCE : render(x));
      }
      return a;
    }
    String s = String.valueOf(rd);
    if (s.length() > 400) {
      s = s.substring(0, 400);
    }
    o.addProperty("type", rd.getClass().getSimpleName());
    o.addProperty("str", s);
    return o;
  }

  @Override
  public void close() throws IOException {
    out.close();
  }
}
