package games.strategy.triplea.ai.harness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A fixed bid read from a {@code <map>.bid.json} file: extra PUs and placed units, applied to the
 * parsed game before round 1 without asking any AI. Units land on the map before the recorder's
 * round-0 snapshot, so they count as their side's starting TUV, not as production.
 *
 * <p>Schema: {@code {"map": "<xml>", "pus": {"<player>": N}, "placements": [{"player", "territory",
 * "unitType", "count"}], "source": "...", "notes": "..."}}.
 */
final class FixedBid {
  private static final Set<String> TOP_KEYS = Set.of("map", "pus", "placements", "source", "notes");
  private static final Set<String> PLACEMENT_KEYS =
      Set.of("player", "territory", "unitType", "count");

  record Placement(String player, String territory, String unitType, int count) {}

  private final Path path;
  private final String sha256;
  private final String map;
  private final Map<String, Integer> pus;
  private final List<Placement> placements;
  private final String source;
  private final String notes;

  private FixedBid(
      final Path path,
      final String sha256,
      final String map,
      final Map<String, Integer> pus,
      final List<Placement> placements,
      final String source,
      final String notes) {
    this.path = path;
    this.sha256 = sha256;
    this.map = map;
    this.pus = pus;
    this.placements = placements;
    this.source = source;
    this.notes = notes;
  }

  static FixedBid load(final Path path) throws IOException {
    final byte[] bytes = Files.readAllBytes(path);
    final JsonObject root = JsonParser.parseString(new String(bytes)).getAsJsonObject();
    for (final String key : root.keySet()) {
      require(TOP_KEYS.contains(key), path + ": unknown key '" + key + "'");
    }
    require(root.has("map"), path + ": missing \"map\"");
    final Map<String, Integer> pus = new TreeMap<>();
    if (root.has("pus")) {
      for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject("pus").entrySet()) {
        pus.put(e.getKey(), nonNegativeInt(e.getValue(), path + ": pus." + e.getKey()));
      }
    }
    final List<Placement> placements = new ArrayList<>();
    final JsonArray array =
        root.has("placements") ? root.getAsJsonArray("placements") : new JsonArray();
    for (int i = 0; i < array.size(); i++) {
      final JsonObject p = array.get(i).getAsJsonObject();
      final String where = path + ": placements[" + i + "]";
      for (final String key : p.keySet()) {
        require(PLACEMENT_KEYS.contains(key), where + ": unknown key '" + key + "'");
      }
      for (final String key : PLACEMENT_KEYS) {
        require(p.has(key), where + ": missing \"" + key + "\"");
      }
      final int count = nonNegativeInt(p.get("count"), where + ".count");
      require(count > 0, where + ".count must be > 0");
      placements.add(
          new Placement(
              p.get("player").getAsString(),
              p.get("territory").getAsString(),
              p.get("unitType").getAsString(),
              count));
    }
    return new FixedBid(
        path.toAbsolutePath(),
        sha256(bytes),
        root.get("map").getAsString(),
        pus,
        placements,
        root.has("source") ? root.get("source").getAsString() : null,
        root.has("notes") ? root.get("notes").getAsString() : null);
  }

  String sha256() {
    return sha256;
  }

  /** The RESULT-line tag: the first 12 hex digits of the file's SHA-256. */
  String shortHash() {
    return sha256.substring(0, 12);
  }

  Path path() {
    return path;
  }

  String summary() {
    return "path=" + path + " sha256=" + sha256 + " pus=" + pus + " placements=" + placements;
  }

  /**
   * Validates the whole bid against {@code data}, then applies it. Fails before changing anything
   * if any entry is wrong, or if the map's own bid steps would also give a bid ("{@code <player>
   * bid}" game options other than 0 make ProAI spend that amount in the {@code *Bid} steps).
   *
   * @return TUV of the placed units by side
   */
  Map<String, Integer> apply(final GameData data, final String mapArg) {
    final List<String> errors = new ArrayList<>();
    if (!map.equals(mapArg)) {
      errors.add("bid is for map " + map + ", game is " + mapArg);
    }
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      final int engineBid = data.getProperties().get(player.getName() + " bid", 0);
      if (engineBid != 0) {
        errors.add(
            "game option '"
                + player.getName()
                + " bid' is "
                + engineBid
                + "; --bid would double up with the map's bid steps (set it to 0)");
      }
    }
    for (final String name : pus.keySet()) {
      if (data.getPlayerList().getPlayerId(name) == null) {
        errors.add("pus: no player " + name);
      }
    }
    for (final Placement p : placements) {
      final String where = "placement " + p + ": ";
      final GamePlayer player = data.getPlayerList().getPlayerId(p.player());
      final Territory territory = data.getMap().getTerritoryOrNull(p.territory());
      final UnitType type = data.getUnitTypeList().getUnitType(p.unitType()).orElse(null);
      if (player == null) {
        errors.add(where + "no player " + p.player());
      }
      if (territory == null) {
        errors.add(where + "no territory " + p.territory());
      }
      if (type == null) {
        errors.add(where + "no unit type " + p.unitType());
      }
      if (player == null || territory == null || type == null) {
        continue;
      }
      final boolean sea = Matches.unitTypeIsSea().test(type);
      if (territory.isWater()) {
        if (!sea) {
          errors.add(where + "only sea units go in a sea zone (no carrier-based air)");
        } else if (data.getMap().getNeighbors(territory).stream()
            .noneMatch(t -> !t.isWater() && friendly(data, t.getOwner(), player))) {
          errors.add(where + "sea zone touches no land owned by " + p.player() + " or an ally");
        }
      } else if (sea) {
        errors.add(where + "sea unit on land");
      } else if (!friendly(data, territory.getOwner(), player)) {
        errors.add(
            where
                + "territory is owned by "
                + territory.getOwner().getName()
                + ", not "
                + p.player()
                + " or an ally");
      }
    }
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(
          "Invalid bid " + path + ":\n  " + String.join("\n  ", errors));
    }
    final Resource pu = data.getResourceList().getResourceOrThrow(Constants.PUS);
    pus.forEach(
        (name, n) -> data.getPlayerList().getPlayerId(name).getResources().addResource(pu, n));
    final TuvCostsCalculator costs = new TuvCostsCalculator();
    final Map<String, Integer> tuvBySide = new TreeMap<>();
    for (final Placement p : placements) {
      final GamePlayer player = data.getPlayerList().getPlayerId(p.player());
      final UnitType type = data.getUnitTypeList().getUnitType(p.unitType()).orElseThrow();
      data.getMap()
          .getTerritoryOrNull(p.territory())
          .getUnitCollection()
          .addAll(type.create(p.count(), player));
      tuvBySide.merge(
          GameRecorder.sideOf(player),
          costs.getCostsForTuv(player).getInt(type) * p.count(),
          Integer::sum);
    }
    return tuvBySide;
  }

  /** The {@code bid} entry of game.json. */
  Map<String, Object> describe(final Map<String, Integer> tuvBySide) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("path", path.toString());
    out.put("sha256", sha256);
    out.put("map", map);
    out.put("pus", pus);
    out.put(
        "placements",
        placements.stream()
            .map(
                p -> {
                  final Map<String, Object> m = new LinkedHashMap<>();
                  m.put("player", p.player());
                  m.put("territory", p.territory());
                  m.put("unitType", p.unitType());
                  m.put("count", p.count());
                  return m;
                })
            .toList());
    out.put("source", source);
    out.put("notes", notes);
    out.put("placed_tuv_by_side", tuvBySide);
    return out;
  }

  private static boolean friendly(final GameData data, final GamePlayer owner, final GamePlayer p) {
    return owner.equals(p) || (!owner.isNull() && data.getRelationshipTracker().isAllied(owner, p));
  }

  private static int nonNegativeInt(final JsonElement e, final String where) {
    require(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber(), where + " must be a number");
    final double d = e.getAsDouble();
    require(d >= 0 && d == Math.rint(d), where + " must be a whole number >= 0: " + d);
    return (int) d;
  }

  private static void require(final boolean ok, final String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }

  private static String sha256(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
