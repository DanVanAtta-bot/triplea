package games.strategy.triplea.ai.harness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.data.properties.IEditableProperty;
import games.strategy.engine.framework.ServerGame;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.triplea.java.collections.IntegerMap;

/**
 * A human position from the forum-save export (JSONL, see scratch/forum-saves-scope.md), rebuilt on
 * the current map XML: game options from the export's meta record, then the turn-start position of
 * one player in one round (owners, units with hits, transport cargo, unplaced units, resources).
 * Gaps: tech advances, v3 factory damage and per-unit flags are not in the export.
 *
 * <p>Spec: {@code <export.jsonl>@<round>@<player>}. A list file holds one spec per line ({@code #}
 * comments allowed); {@link #pick} chooses the line for a runner game from its out dir.
 */
final class HumanStart {
  final Path export;
  final int round;
  final String player;
  final JsonObject meta;
  final JsonObject position;

  private HumanStart(
      final Path export,
      final int round,
      final String player,
      final JsonObject meta,
      final JsonObject position) {
    this.export = export;
    this.round = round;
    this.player = player;
    this.meta = meta;
    this.position = position;
  }

  String spec() {
    return export + "@" + round + "@" + player;
  }

  private static final Pattern RUNNER_GAME = Pattern.compile("p(\\d+)-([ab])");

  /**
   * The list line for a runner game: {@code index = 2 * pair + (slot b ? 1 : 0)}, modulo the list
   * size, with pair and slot parsed from {@code key} (the runner's {@code {game_dir}}, eg {@code
   * runs/job/games/p00004-b.1}). A job with {@code pairs = lines * reps / 2} plays every line
   * {@code reps} times.
   */
  static String pick(final Path listFile, final String key) throws IOException {
    final List<String> lines =
        Files.readAllLines(listFile).stream()
            .map(String::trim)
            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
            .toList();
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("No specs in " + listFile);
    }
    final Matcher m = RUNNER_GAME.matcher(key);
    int index = 0;
    while (m.find()) {
      index = 2 * Integer.parseInt(m.group(1)) + (m.group(2).equals("b") ? 1 : 0);
    }
    return lines.get(index % lines.size());
  }

  static HumanStart read(final String spec) throws IOException {
    final String[] parts = spec.split("@");
    if (parts.length != 3) {
      throw new IllegalArgumentException(
          "Start spec must be <export.jsonl>@<round>@<player>: " + spec);
    }
    final Path export = Path.of(parts[0]);
    final int round = Integer.parseInt(parts[1]);
    final String player = parts[2];
    JsonObject meta = null;
    JsonObject candidate = null;
    JsonObject position = null;
    try (BufferedReader reader = Files.newBufferedReader(export)) {
      String line;
      while (position == null && (line = reader.readLine()) != null) {
        // Cheap pre-filter: most lines are events.
        if (!line.startsWith("{\"t\":\"meta\"")
            && !line.startsWith("{\"t\":\"position\"")
            && !line.startsWith("{\"t\":\"step\"")) {
          continue;
        }
        final JsonObject o = JsonParser.parseString(line).getAsJsonObject();
        switch (o.get("t").getAsString()) {
          case "meta" -> meta = o;
          case "position" -> {
            final boolean match =
                o.has("player")
                    && !o.get("player").isJsonNull()
                    && o.get("round").getAsInt() == round
                    && o.get("player").getAsString().equals(player);
            candidate = match ? o : null;
          }
          case "step" -> {
            // A turn_start that only opens the player's round-1 bid steps is not the turn start.
            if (candidate != null) {
              if (!isBidStep(o.get("step").getAsString())) {
                position = candidate;
              }
              candidate = null;
            }
          }
          default -> {}
        }
      }
    }
    if (meta == null || position == null) {
      throw new IllegalArgumentException("No turn_start position for " + spec);
    }
    return new HumanStart(export, round, player, meta, position);
  }

  static boolean isBidStep(final String stepName) {
    return stepName.toLowerCase().contains("bid");
  }

  /**
   * Game options the human game was played with, as name -> value strings for {@link
   * AiMatch#setProperty}; bids are dropped (the position already holds the bid units).
   */
  Map<String, String> options(final GameData data) {
    final Map<String, String> out = new HashMap<>();
    final Map<String, IEditableProperty<?>> editable =
        data.getProperties().getEditablePropertiesByName();
    for (final Map.Entry<String, JsonElement> e : meta.getAsJsonObject("options").entrySet()) {
      if (editable.containsKey(e.getKey()) && !e.getKey().toLowerCase().endsWith(" bid")) {
        out.put(e.getKey(), e.getValue().getAsString());
      }
    }
    return out;
  }

  /**
   * Runs the game-init steps, injects the position, marks the round-1 bid steps as run and jumps to
   * the player's first step of the round. Returns notes on anything the map lacks.
   */
  List<String> apply(final ServerGame game) {
    final GameData data = game.getData();
    while (data.getSequence().getStep().getPlayerId() == null) {
      game.runNextStep();
    }
    final List<String> missing = new ArrayList<>();
    try (GameData.Unlocker ignored = data.acquireWriteLock()) {
      data.performChange(inject(data, missing));
      GameStep first = null;
      for (final GameStep s : data.getSequence()) {
        if (isBidStep(s.getName())) {
          while (s.getMaxRunCount() > 0 && !s.hasReachedMaxRunCount()) {
            s.incrementRunCount();
          }
        } else if (first == null && player.equals(name(s.getPlayerId()))) {
          first = s;
        }
      }
      if (first == null) {
        throw new IllegalArgumentException("No step for " + player);
      }
      data.getSequence().setRoundAndStep(round, first.getDisplayName(), first.getPlayerId());
      final GameStep landed = data.getSequence().getStep();
      if (!landed.getName().equals(first.getName()) || data.getSequence().getRound() != round) {
        throw new IllegalStateException(
            "setRoundAndStep landed on " + landed.getName() + " instead of " + first.getName());
      }
    }
    return missing;
  }

  private static String name(final GamePlayer p) {
    return p == null ? null : p.getName();
  }

  private CompositeChange inject(final GameData data, final List<String> missing) {
    final CompositeChange change = new CompositeChange();
    for (final Map.Entry<String, JsonElement> te :
        position.getAsJsonObject("territories").entrySet()) {
      final JsonObject tj = te.getValue().getAsJsonObject();
      if (te.getKey().startsWith("@")) {
        final GamePlayer p = data.getPlayerList().getPlayerId(te.getKey().substring(1));
        if (p == null) {
          missing.add("player " + te.getKey());
          continue;
        }
        change.add(ChangeFactory.removeUnits(p, new ArrayList<>(p.getUnitCollection().getUnits())));
        final List<Unit> add = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> ue : tj.getAsJsonObject("u").entrySet()) {
          final String[] k = ue.getKey().split("\\|");
          final int n = ue.getValue().getAsInt();
          if (k[0].equals("res")) {
            final Resource r = data.getResourceList().getResourceOptional(k[1]).orElse(null);
            if (r == null) {
              missing.add("resource " + k[1]);
              continue;
            }
            change.add(
                ChangeFactory.changeResourcesChange(p, r, n - p.getResources().getQuantity(r)));
          } else {
            final UnitType ut = data.getUnitTypeList().getUnitType(k[1]).orElse(null);
            if (ut == null) {
              missing.add("unit type " + k[1]);
              continue;
            }
            add.addAll(ut.create(n, p));
          }
        }
        change.add(ChangeFactory.addUnits(p, add));
        continue;
      }
      final Territory t = data.getMap().getTerritoryOrNull(te.getKey());
      if (t == null) {
        missing.add("territory " + te.getKey());
        continue;
      }
      final GamePlayer owner =
          tj.get("o").isJsonNull()
              ? null
              : data.getPlayerList().getPlayerId(tj.get("o").getAsString());
      final GamePlayer op = owner == null ? data.getPlayerList().getNullPlayer() : owner;
      if (!op.equals(t.getOwner())) {
        change.add(ChangeFactory.changeOwner(t, op));
      }
      change.add(ChangeFactory.removeUnits(t, new ArrayList<>(t.getUnitCollection().getUnits())));
      final List<Unit> add = new ArrayList<>();
      final IntegerMap<Unit> hits = new IntegerMap<>();
      final Map<String, List<Unit>> byKey = new HashMap<>();
      for (final Map.Entry<String, JsonElement> ue : tj.getAsJsonObject("u").entrySet()) {
        final String[] k = ue.getKey().split("\\|");
        final GamePlayer up = data.getPlayerList().getPlayerId(k[0]);
        final UnitType ut = data.getUnitTypeList().getUnitType(k[1]).orElse(null);
        if (up == null || ut == null) {
          missing.add("unit " + ue.getKey() + " in " + te.getKey());
          continue;
        }
        final List<Unit> us = ut.create(ue.getValue().getAsInt(), up);
        if (k.length > 2 && k[2].startsWith("hit")) {
          for (final Unit u : us) {
            hits.put(u, Integer.parseInt(k[2].substring(3)));
          }
        }
        add.addAll(us);
        byKey.computeIfAbsent(k[0] + "|" + k[1], x -> new ArrayList<>()).addAll(us);
      }
      change.add(ChangeFactory.addUnits(t, add));
      if (!hits.isEmpty()) {
        change.add(ChangeFactory.unitsHit(hits, List.of(t)));
      }
      if (tj.has("cargo")) {
        for (final JsonElement ce : tj.getAsJsonArray("cargo")) {
          final JsonObject c = ce.getAsJsonObject();
          final List<Unit> transports = byKey.get(c.get("by").getAsString());
          if (transports == null || transports.isEmpty()) {
            missing.add("transport " + c.get("by") + " in " + te.getKey());
            continue;
          }
          final Unit transport = transports.remove(0);
          for (final Map.Entry<String, JsonElement> ue : c.getAsJsonObject("u").entrySet()) {
            final List<Unit> pool = byKey.get(ue.getKey());
            for (int i = 0; i < ue.getValue().getAsInt() && pool != null && !pool.isEmpty(); i++) {
              change.add(
                  ChangeFactory.unitPropertyChange(
                      pool.remove(0), transport, Unit.PropertyName.TRANSPORTED_BY));
            }
          }
        }
      }
    }
    return change;
  }
}
