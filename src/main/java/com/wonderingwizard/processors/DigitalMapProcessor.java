package com.wonderingwizard.processors;

import com.wonderingwizard.domain.YardLocation;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.DigitalMapEvent;
import com.wonderingwizard.events.WorkInstructionEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Processor that handles digital map events and provides pathfinding-based
 * duration enrichment for TT drive actions during schedule creation.
 *
 * <p>This processor has a dual role:
 * <ul>
 *   <li><b>EventProcessor:</b> Reacts to {@link DigitalMapEvent} to parse and store
 *       the terminal map graph. Produces no side effects.</li>
 *   <li><b>SchedulePipelineStep:</b> Called by {@link WorkQueueProcessor} during
 *       schedule creation to compute travel durations between positions using
 *       the stored map. Adjusts TT drive action durations based on pathfinding results.</li>
 * </ul>
 *
 * <p>The digital map payload is a JSON envelope containing a {@code terminalLayout} field
 * which holds a base64-encoded, gzip-compressed OSM XML document describing the terminal
 * road network with nodes (positions with lat/lon) and ways (roads with speed limits).
 *
 * <p>Existing schedules are not affected when a new map arrives. Only schedules
 * created after the map update will use the new map data. If no map has been loaded,
 * the pipeline step passes templates through unchanged.
 */
public class DigitalMapProcessor implements EventProcessor, SchedulePipelineStep {

    private static final Logger logger = Logger.getLogger(DigitalMapProcessor.class.getName());
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double DEFAULT_SPEED_KMH = 10.0;

    private record NodeCoord(double lat, double lon) {}
    record GraphEdge(String toNodeId, double durationSeconds, int priority) {
        /** Weight used for pathfinding: duration × priority (matching C# WeightCalculator). */
        double weight() { return (durationSeconds / 60.0) * priority; }
    }

    /** Precomputed POI-to-POI durations: "fromName\0toName" → duration in seconds (-1 if unreachable) */
    private final Map<String, Integer> poiDurations = new HashMap<>();
    private boolean mapLoaded = false;

    private static final String POI_TAG_NAME = "name";
    private static final String POI_ALT_NAME_TAG = "alt_name";
    private static final String POI_STANDBY_TAG = "apmt_poi_standby_bay";
    private static final String POI_STANDBY_40_TAG = "apmt_poi_standby_bay_40";
    private static final Set<String> VALID_APMT_TAG_KEYS = Set.of(
            "apmt_average_speed", "apmt_route_priority", "apmt_service_block",
            "apmt_poi_standby_bay", "apmt_poi_standby_bay_40");
    private static final Set<String> VALID_HIGHWAY_VALUES = Set.of(
            "service", "secondary", "primary", "tertiary", "residential", "unclassified", "trunk");

    // Visualization data retained after parsing
    public record PoiInfo(String name, double lat, double lon) {}
    public record RoadSegment(double lat1, double lon1, double lat2, double lon2, double speedKmh, boolean oneway,
                                  String highway, String name, int lanes, int priority) {}
    private final List<PoiInfo> pois = new ArrayList<>();
    private final List<RoadSegment> roadSegments = new ArrayList<>();
    /** POI name → standby POI name, as declared by apmt_poi_standby_bay tag (20ft) */
    private final Map<String, String> standbyLocations = new HashMap<>();
    /** POI name → standby POI name for 40ft containers, as declared by apmt_poi_standby_bay_40 tag */
    private final Map<String, String> standbyLocations40 = new HashMap<>();
    /** Retained graph and node coordinates for on-demand pathfinding (path reconstruction) */
    private Map<String, List<GraphEdge>> graph = Map.of();
    private Map<String, NodeCoord> nodeCoords = Map.of();
    private Map<String, String> poiToRoadNode = Map.of();

    @Override
    public List<SideEffect> process(Event event) {
        if (event instanceof DigitalMapEvent mapEvent) {
            parseMap(mapEvent.mapPayload());
        }
        return List.of();
    }

    /**
     * Parses the digital map payload. The payload is a JSON object with a
     * {@code terminalLayout} field containing base64-encoded gzip-compressed OSM XML.
     */
    void parseMap(String payload) {
        poiDurations.clear();
        pois.clear();
        roadSegments.clear();
        standbyLocations.clear();
        standbyLocations40.clear();
        mapLoaded = false;

        if (payload == null || payload.isBlank()) {
            logger.warning("Empty digital map payload");
            return;
        }

        String osmXml = extractAndDecompressLayout(payload);
        if (osmXml == null || osmXml.isEmpty()) {
            logger.warning("Could not extract terminal layout from digital map payload");
            return;
        }

        buildGraphAndPrecompute(osmXml);
        mapLoaded = true;
        logger.info("Digital map loaded: " + poiDurations.size() + " precomputed POI pairs");
    }

    private String extractAndDecompressLayout(String payload) {
        // Extract terminalLayout field from JSON
        String layoutB64 = extractJsonString(payload, "terminalLayout");
        if (layoutB64 == null || layoutB64.isEmpty()) {
            logger.warning("Digital map payload missing 'terminalLayout' field");
            return null;
        }

        try {
            // Strip any whitespace/newlines that might be in the base64 string
            layoutB64 = layoutB64.replaceAll("\\s", "");
            // Try decoding, adding padding if needed (some encoders omit trailing '=')
            byte[] compressed = decodeBase64Lenient(layoutB64);
            return decompressGzip(compressed);
        } catch (Exception e) {
            logger.warning("Failed to decode/decompress terminal layout: " + e.getMessage());
            return null;
        }
    }

    private static byte[] decodeBase64Lenient(String b64) {
        // Some encoders produce concatenated base64 segments (e.g., "...AA==51W...").
        // .NET's Convert.FromBase64String handles this transparently; Java does not.
        // Split on segment boundaries (padding followed by non-padding), decode each,
        // and concatenate the results.
        var segments = b64.split("(?<==)(?=[^=])");
        if (segments.length == 1) {
            return Base64.getDecoder().decode(padBase64(b64));
        }
        var baos = new ByteArrayOutputStream();
        for (var segment : segments) {
            byte[] decoded = Base64.getDecoder().decode(padBase64(segment));
            baos.writeBytes(decoded);
        }
        return baos.toByteArray();
    }

    private static String padBase64(String b64) {
        String stripped = b64.replaceAll("=+$", "");
        int remainder = stripped.length() % 4;
        return switch (remainder) {
            case 2 -> stripped + "==";
            case 3 -> stripped + "=";
            default -> stripped;
        };
    }

    private String decompressGzip(byte[] compressed) throws IOException {
        try (var bais = new ByteArrayInputStream(compressed);
             var gzis = new GZIPInputStream(bais);
             var baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            try {
                int len;
                while ((len = gzis.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
            } catch (java.io.EOFException e) {
                // Truncated gzip stream — use what we have
                logger.fine("Truncated gzip stream, using partial data (" + baos.size() + " bytes)");
            }
            return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // ── OSM XML Parsing ──────────────────────────────────────────────

    private static final Pattern NODE_PATTERN = Pattern.compile(
            "<node\\s+([^>]*)/?>");
    private static final Pattern NODE_WITH_TAGS_PATTERN = Pattern.compile(
            "<node\\s+id=\"(\\d+)\"[^>]*>(.*?)</node>", Pattern.DOTALL);
    private static final Pattern WAY_PATTERN = Pattern.compile(
            "<way\\s+id=\"\\d+\"[^>]*>(.*?)</way>", Pattern.DOTALL);
    private static final Pattern TAG_PATTERN = Pattern.compile(
            "<tag\\s+k=\"([^\"]+)\"\\s+v=\"([^\"]+)\"\\s*/>");
    private static final Pattern ND_REF_PATTERN = Pattern.compile(
            "<nd\\s+ref=\"(\\d+)\"\\s*/>");
    private static final Pattern ATTR_ID = Pattern.compile("id=\"(\\d+)\"");
    private static final Pattern ATTR_LAT = Pattern.compile("lat=\"([^\"]+)\"");
    private static final Pattern ATTR_LON = Pattern.compile("lon=\"([^\"]+)\"");

    private void buildGraphAndPrecompute(String osmXml) {
        // Step 1: Parse all node coordinates
        var nodeCoords = new HashMap<String, NodeCoord>();
        Matcher nodeMatcher = NODE_PATTERN.matcher(osmXml);
        while (nodeMatcher.find()) {
            String attrs = nodeMatcher.group(1);
            Matcher idM = ATTR_ID.matcher(attrs);
            Matcher latM = ATTR_LAT.matcher(attrs);
            Matcher lonM = ATTR_LON.matcher(attrs);
            if (idM.find() && latM.find() && lonM.find()) {
                nodeCoords.put(idM.group(1),
                        new NodeCoord(Double.parseDouble(latM.group(1)), Double.parseDouble(lonM.group(1))));
            }
        }

        // Step 2: Parse POI destination nodes (nodes with amenity=apmt_poi_destination)
        // Each node can have a name (20ft) and alt_name (40ft), each with its own standby tag.
        var poiNameToNodeId = new HashMap<String, String>();
        Matcher nodeWithTagsMatcher = NODE_WITH_TAGS_PATTERN.matcher(osmXml);
        while (nodeWithTagsMatcher.find()) {
            String nodeId = nodeWithTagsMatcher.group(1);
            String content = nodeWithTagsMatcher.group(2);
            var tags = parseTags(content);
            if (!"apmt_poi_destination".equals(tags.get("amenity"))) {
                continue;
            }

            // Primary POI (name tag, apmt_poi_standby_bay)
            String poiName = tags.get(POI_TAG_NAME);
            if (poiName != null) {
                poiNameToNodeId.put(poiName, nodeId);
                NodeCoord coord = nodeCoords.get(nodeId);
                if (coord != null) {
                    pois.add(new PoiInfo(poiName, coord.lat(), coord.lon()));
                }
                String standby = tags.get(POI_STANDBY_TAG);
                if (standby != null && !standby.isEmpty()) {
                    standbyLocations.put(poiName, standby);
                }
            }

            // 40ft POI (alt_name tag, apmt_poi_standby_bay_40)
            String altName = tags.get(POI_ALT_NAME_TAG);
            if (altName != null) {
                poiNameToNodeId.put(altName, nodeId);
                NodeCoord coord = nodeCoords.get(nodeId);
                if (coord != null && poiName == null) {
                    // Only add to POI list if no primary name (avoid duplicate markers)
                    pois.add(new PoiInfo(altName, coord.lat(), coord.lon()));
                }
                String standby40 = tags.get(POI_STANDBY_40_TAG);
                if (standby40 != null && !standby40.isEmpty()) {
                    standbyLocations40.put(altName, standby40);
                }
            }
        }

        // Step 3: Parse highway ways and build road graph
        var graphLocal = new HashMap<String, List<GraphEdge>>();
        var roadNodeIds = new java.util.HashSet<String>();
        Matcher wayMatcher = WAY_PATTERN.matcher(osmXml);
        while (wayMatcher.find()) {
            String content = wayMatcher.group(1);
            var tags = parseTags(content);
            String highwayValue = tags.get("highway");
            if (highwayValue == null) {
                continue;
            }
            // Skip ways with corrupted tags (from truncated gzip decompression).
            // The gzip stream is often truncated, producing garbled XML at the tail.
            // Detect corruption by checking: (1) tag keys contain only valid chars,
            // (2) no unexpected tag keys (misspelled variants from corruption),
            // (3) highway value is a known OSM type.
            if (tags.keySet().stream().anyMatch(k -> !k.matches("[a-z_:]+"))
                    || tags.keySet().stream().anyMatch(k -> k.startsWith("apmt_") && !VALID_APMT_TAG_KEYS.contains(k))
                    || !VALID_HIGHWAY_VALUES.contains(highwayValue)) {
                logger.fine("Skipping way with corrupted tags: " + tags);
                continue;
            }

            double speedKmh = DEFAULT_SPEED_KMH;
            String speedStr = tags.get("apmt_average_speed");
            if (speedStr != null) {
                try {
                    speedKmh = Double.parseDouble(speedStr);
                } catch (NumberFormatException ignored) {
                }
            }
            double speedMs = speedKmh * 1000.0 / 3600.0;
            boolean oneway = "yes".equals(tags.get("oneway"));

            List<String> refs = parseNdRefs(content);
            roadNodeIds.addAll(refs);

            for (int i = 0; i < refs.size() - 1; i++) {
                String n1 = refs.get(i);
                String n2 = refs.get(i + 1);
                NodeCoord c1 = nodeCoords.get(n1);
                NodeCoord c2 = nodeCoords.get(n2);
                if (c1 == null || c2 == null) {
                    continue;
                }

                double distMeters = haversine(c1.lat(), c1.lon(), c2.lat(), c2.lon());
                double durationSec = speedMs > 0 ? distMeters / speedMs : 9999;

                String roadName = tags.getOrDefault("name", "");
                int lanesCount = 1;
                String lanesStr = tags.get("lanes");
                if (lanesStr != null) {
                    try { lanesCount = Integer.parseInt(lanesStr); } catch (NumberFormatException ignored) {}
                }
                int routePriority = 100;
                String prioStr = tags.get("apmt_route_priority");
                if (prioStr != null) {
                    try { routePriority = Integer.parseInt(prioStr); } catch (NumberFormatException ignored) {}
                }
                roadSegments.add(new RoadSegment(c1.lat(), c1.lon(), c2.lat(), c2.lon(), speedKmh, oneway,
                        tags.getOrDefault("highway", ""), roadName, lanesCount, routePriority));

                graphLocal.computeIfAbsent(n1, k -> new ArrayList<>())
                        .add(new GraphEdge(n2, durationSec, routePriority));
                if (!oneway) {
                    graphLocal.computeIfAbsent(n2, k -> new ArrayList<>())
                            .add(new GraphEdge(n1, durationSec, routePriority));
                }
            }
        }

        // Validate standby locations reference existing POIs
        var allPoiNames = poiNameToNodeId.keySet();
        for (var entry : standbyLocations.entrySet()) {
            if (!allPoiNames.contains(entry.getValue())) {
                logger.warning("Standby location '" + entry.getValue()
                        + "' referenced by POI '" + entry.getKey() + "' does not exist as a POI");
            }
        }
        for (var entry : standbyLocations40.entrySet()) {
            if (!allPoiNames.contains(entry.getValue())) {
                logger.warning("40ft standby location '" + entry.getValue()
                        + "' referenced by POI '" + entry.getKey() + "' does not exist as a POI");
            }
        }

        // Step 4: Snap POI nodes to nearest road node
        var poiToRoadNodeLocal = new HashMap<String, String>();
        for (var entry : poiNameToNodeId.entrySet()) {
            String poiNodeId = entry.getValue();
            if (roadNodeIds.contains(poiNodeId)) {
                poiToRoadNodeLocal.put(poiNodeId, poiNodeId);
            } else {
                NodeCoord poiCoord = nodeCoords.get(poiNodeId);
                if (poiCoord == null) {
                    continue;
                }
                String nearest = null;
                double bestDist = Double.MAX_VALUE;
                for (String roadId : roadNodeIds) {
                    NodeCoord roadCoord = nodeCoords.get(roadId);
                    if (roadCoord == null) {
                        continue;
                    }
                    double d = squaredDist(poiCoord, roadCoord);
                    if (d < bestDist) {
                        bestDist = d;
                        nearest = roadId;
                    }
                }
                if (nearest != null) {
                    poiToRoadNodeLocal.put(poiNodeId, nearest);
                }
            }
        }

        // Step 5: Precompute all POI-to-POI shortest path durations
        // Run Dijkstra once from each unique road node that a POI maps to,
        // then populate the lookup table.
        var roadNodeToPois = new HashMap<String, List<String>>();
        for (var entry : poiNameToNodeId.entrySet()) {
            String roadNode = poiToRoadNodeLocal.get(entry.getValue());
            if (roadNode == null) {
                roadNode = entry.getValue();
            }
            roadNodeToPois.computeIfAbsent(roadNode, k -> new ArrayList<>()).add(entry.getKey());
        }

        // Collect all target road nodes for early termination
        var allPoiRoadNodes = new java.util.HashSet<>(roadNodeToPois.keySet());

        for (var entry : roadNodeToPois.entrySet()) {
            String sourceRoadNode = entry.getKey();
            List<String> sourcePoiNames = entry.getValue();

            // Single-source Dijkstra from this road node to all reachable road nodes
            var dist = dijkstraAll(graphLocal, sourceRoadNode, allPoiRoadNodes);

            // Populate duration lookup for each source POI → all target POIs
            for (String fromName : sourcePoiNames) {
                for (var targetEntry : roadNodeToPois.entrySet()) {
                    String targetRoadNode = targetEntry.getKey();
                    Double d = dist.get(targetRoadNode);
                    int duration = d != null ? (int) Math.round(d) : -1;
                    for (String toName : targetEntry.getValue()) {
                        poiDurations.put(durationKey(fromName, toName), duration);
                    }
                }
            }
        }

        // Retain graph and coordinates for on-demand path reconstruction
        this.graph = graphLocal;
        this.nodeCoords = nodeCoords;
        // Build POI name → road node mapping (for path queries by POI name)
        var poiNameToRoadNode = new HashMap<String, String>();
        for (var entry : poiNameToNodeId.entrySet()) {
            String roadNode = poiToRoadNodeLocal.get(entry.getValue());
            poiNameToRoadNode.put(entry.getKey(), roadNode != null ? roadNode : entry.getValue());
        }
        this.poiToRoadNode = poiNameToRoadNode;
    }

    private Map<String, String> parseTags(String content) {
        var tags = new HashMap<String, String>();
        Matcher m = TAG_PATTERN.matcher(content);
        while (m.find()) {
            tags.put(m.group(1), m.group(2));
        }
        return tags;
    }

    private List<String> parseNdRefs(String content) {
        var refs = new ArrayList<String>();
        Matcher m = ND_REF_PATTERN.matcher(content);
        while (m.find()) {
            refs.add(m.group(1));
        }
        return refs;
    }

    // ── Geo helpers ──────────────────────────────────────────────────

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double squaredDist(NodeCoord a, NodeCoord b) {
        double dLat = a.lat() - b.lat();
        double dLon = a.lon() - b.lon();
        return dLat * dLat + dLon * dLon;
    }

    // ── Pathfinding (precomputed) ───────────────────────────────────

    /**
     * Looks up the precomputed shortest path duration between two POI names.
     *
     * @param fromName the starting POI name (e.g., "1A25")
     * @param toName the destination POI name (e.g., "B52")
     * @return the total travel duration in seconds, or -1 if no path exists
     */
    public int findPathDuration(String fromName, String toName) {
        if (!mapLoaded || fromName == null || toName == null) {
            return -1;
        }
        if (fromName.equals(toName)) {
            return 0;
        }
        return poiDurations.getOrDefault(durationKey(fromName, toName), -1);
    }

    private static String durationKey(String from, String to) {
        return from + "\0" + to;
    }

    /**
     * Finds the shortest path between two POIs and returns the list of coordinates.
     *
     * @return list of [lat, lon] pairs along the path, or empty list if no path found
     */
    public List<double[]> findPath(String fromName, String toName) {
        if (!mapLoaded || fromName == null || toName == null || graph.isEmpty()) {
            return List.of();
        }
        String startNode = poiToRoadNode.get(fromName);
        String endNode = poiToRoadNode.get(toName);
        if (startNode == null || endNode == null) {
            return List.of();
        }
        if (startNode.equals(endNode)) {
            NodeCoord c = nodeCoords.get(startNode);
            return c != null ? List.of(new double[]{c.lat(), c.lon()}) : List.of();
        }

        // Dijkstra with predecessor tracking
        var weight = new HashMap<String, Double>();
        var prev = new HashMap<String, String>();
        var pq = new PriorityQueue<Map.Entry<String, Double>>(Comparator.comparingDouble(Map.Entry::getValue));
        weight.put(startNode, 0.0);
        pq.add(Map.entry(startNode, 0.0));

        while (!pq.isEmpty()) {
            var current = pq.poll();
            String node = current.getKey();
            double w = current.getValue();
            if (w > weight.getOrDefault(node, Double.MAX_VALUE)) continue;
            if (node.equals(endNode)) break;
            for (var edge : graph.getOrDefault(node, List.of())) {
                double newWeight = w + edge.weight();
                if (newWeight < weight.getOrDefault(edge.toNodeId(), Double.MAX_VALUE)) {
                    weight.put(edge.toNodeId(), newWeight);
                    prev.put(edge.toNodeId(), node);
                    pq.add(Map.entry(edge.toNodeId(), newWeight));
                }
            }
        }

        if (!prev.containsKey(endNode)) {
            return List.of();
        }

        // Reconstruct path
        var path = new ArrayList<String>();
        for (String n = endNode; n != null; n = prev.get(n)) {
            path.add(n);
        }
        java.util.Collections.reverse(path);

        // Convert to coordinates
        var coords = new ArrayList<double[]>();
        for (String n : path) {
            NodeCoord c = nodeCoords.get(n);
            if (c != null) {
                coords.add(new double[]{c.lat(), c.lon()});
            }
        }
        return coords;
    }

    /**
     * Single-source Dijkstra that returns actual durations (seconds) to all reachable nodes
     * in {@code targets}. Routing uses priority-weighted costs (driveTime × priority) to
     * prefer higher-priority roads, matching the C# WeightCalculator behaviour.
     * Stops early once all targets have been settled.
     */
    private static Map<String, Double> dijkstraAll(
            Map<String, List<GraphEdge>> graph, String start, java.util.Set<String> targets) {
        var weight = new HashMap<String, Double>();   // weighted cost used for routing
        var duration = new HashMap<String, Double>(); // actual travel duration in seconds
        var pq = new PriorityQueue<Map.Entry<String, Double>>(Comparator.comparingDouble(Map.Entry::getValue));
        weight.put(start, 0.0);
        duration.put(start, 0.0);
        pq.add(Map.entry(start, 0.0));
        int settled = 0;
        int targetCount = targets.size();

        while (!pq.isEmpty() && settled < targetCount) {
            var current = pq.poll();
            String node = current.getKey();
            double w = current.getValue();

            if (w > weight.getOrDefault(node, Double.MAX_VALUE)) {
                continue;
            }
            if (targets.contains(node)) {
                settled++;
            }

            for (var edge : graph.getOrDefault(node, List.of())) {
                double newWeight = w + edge.weight();
                if (newWeight < weight.getOrDefault(edge.toNodeId(), Double.MAX_VALUE)) {
                    weight.put(edge.toNodeId(), newWeight);
                    duration.put(edge.toNodeId(), duration.get(node) + edge.durationSeconds());
                    pq.add(Map.entry(edge.toNodeId(), newWeight));
                }
            }
        }

        return duration;
    }

    // ── SchedulePipelineStep ─────────────────────────────────────────

    private static final String BERTH_POI = "B52";
    private static final int FIXED_DURATION = 20;
    private static final int FALLBACK_DURATION = 60;

    @Override
    public List<GraphScheduleBuilder.ActionTemplate> enrichTemplates(
            EnrichmentContext context,
            List<GraphScheduleBuilder.ActionTemplate> templates,
            WorkInstructionEvent workInstruction
    ) {
        if (!mapLoaded) {
            return templates;
        }

        String yardPoi = toYardPoi(workInstruction.toPosition());
        if (yardPoi == null) {
            return templates;
        }

        String standbyPoi = findStandbyLocation(yardPoi);
        String bollard = context.bollardPosition();

        // --- Circular drive calculation for DSCH ---
        // TT drives in a circle: RTG standby → RTG pull (20s) → load → QC standby → QC pull (20s) → unload → RTG standby
        //
        // QC standby = toPosition.standby → bollard minus the 20s pull
        // RTG standby = bollard → toPosition.standby minus the 20s pull
        //
        // For consecutive singles: RTG standby = previousToPosition → currentToPosition.standby

        int qcStandbyDuration;
        int rtgStandbyDuration;

        if (bollard != null && !bollard.isBlank()) {
            // Drive TO QC: from RTG standby to bollard
            // QC standby = standbyPoi → bollard - 20s pull
            int standbyToBollard = standbyPoi != null
                    ? findPathDurationOrDefault(standbyPoi, bollard)
                    : FALLBACK_DURATION;
            qcStandbyDuration = Math.max(1, standbyToBollard - FIXED_DURATION);

            // Drive TO RTG: from bollard to RTG standby
            if (context.containerIndex() > 0 && context.previousToPosition() != null) {
                // Consecutive single: drive from previous RTG position to current standby
                String prevYardPoi = toYardPoi(context.previousToPosition());
                if (prevYardPoi != null && standbyPoi != null) {
                    rtgStandbyDuration = Math.max(1,
                            findPathDurationOrDefault(prevYardPoi, standbyPoi) - FIXED_DURATION);
                } else {
                    rtgStandbyDuration = FALLBACK_DURATION;
                }
            } else {
                // First container: drive from bollard to RTG standby
                int bollardToStandby = standbyPoi != null
                        ? findPathDurationOrDefault(bollard, standbyPoi)
                        : FALLBACK_DURATION;
                rtgStandbyDuration = Math.max(1, bollardToStandby - FIXED_DURATION);
            }
        } else {
            // No bollard — fallback to old berth-based logic
            int yardToBerth = findPathDurationOrDefault(yardPoi, BERTH_POI);
            qcStandbyDuration = Math.max(1, yardToBerth - FIXED_DURATION);
            rtgStandbyDuration = standbyPoi != null
                    ? findPathDurationOrDefault(BERTH_POI, standbyPoi)
                    : FALLBACK_DURATION;
        }

        return adjustTtDurations(templates, qcStandbyDuration, rtgStandbyDuration);
    }

    /**
     * Extracts the block+bay POI name from a yard position string.
     * E.g. {@code Y-PTM-1L20E4} → {@code 1L20}.
     */
    static String toYardPoi(String toPosition) {
        YardLocation loc = YardLocation.parse(toPosition);
        if (loc == null) {
            return null;
        }
        return loc.block() + loc.bay();
    }

    private int findPathDurationOrDefault(String from, String to) {
        int duration = findPathDuration(from, to);
        return duration > 0 ? duration : FALLBACK_DURATION;
    }

    /**
     * Sets each TT action duration to the specified value per action type.
     */
    private List<GraphScheduleBuilder.ActionTemplate> adjustTtDurations(
            List<GraphScheduleBuilder.ActionTemplate> templates,
            int qcStandbyDuration,
            int rtgStandbyDuration) {

        var result = new ArrayList<GraphScheduleBuilder.ActionTemplate>(templates.size());
        for (var tmpl : templates) {
            if (tmpl.deviceType() != DeviceType.TT) {
                result.add(tmpl);
                continue;
            }
            int duration = switch (tmpl.actionType()) {
                case TT_DRIVE_TO_QC_PULL -> FIXED_DURATION;
                case TT_DRIVE_UNDER_QC -> FIXED_DURATION;
                case TT_DRIVE_TO_QC_STANDBY -> qcStandbyDuration;
                case TT_HANDOVER_FROM_QC -> FIXED_DURATION;
                case TT_DRIVE_TO_RTG_PULL -> FIXED_DURATION;
                case TT_DRIVE_TO_RTG_STANDBY -> rtgStandbyDuration;
                case TT_DRIVE_TO_RTG_UNDER -> FIXED_DURATION;
                case TT_HANDOVER_TO_RTG -> FIXED_DURATION;
                case TT_DRIVE_TO_BUFFER -> 1;
                default -> tmpl.durationSeconds();
            };
            result.add(tmpl.withDuration(duration));
        }
        return result;
    }

    public boolean isMapLoaded() {
        return mapLoaded;
    }

    public List<PoiInfo> getPois() {
        return Collections.unmodifiableList(pois);
    }

    public List<RoadSegment> getRoadSegments() {
        return Collections.unmodifiableList(roadSegments);
    }

    /**
     * Finds the 20ft standby location for the given POI, as declared by the
     * {@code apmt_poi_standby_bay} tag on the POI node in the OSM data.
     *
     * @param poiName the name of the POI to find a standby for
     * @return the standby POI name, or null if not found or no standby declared
     */
    public String findStandbyLocation(String poiName) {
        if (!mapLoaded || poiName == null || poiName.isBlank()) {
            return null;
        }
        return standbyLocations.get(poiName);
    }

    /**
     * Finds the 40ft standby location for the given POI, as declared by the
     * {@code apmt_poi_standby_bay_40} tag on the POI node in the OSM data.
     *
     * @param poiName the name of the POI (from alt_name tag) to find a 40ft standby for
     * @return the standby POI name, or null if not found or no standby declared
     */
    public String findStandbyLocation40(String poiName) {
        if (!mapLoaded || poiName == null || poiName.isBlank()) {
            return null;
        }
        return standbyLocations40.get(poiName);
    }

    // ── JSON helpers (no external library) ───────────────────────────

    private String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx == -1) {
            return null;
        }
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx == -1) {
            return null;
        }
        int firstQuote = json.indexOf('"', colonIdx + 1);
        if (firstQuote == -1) {
            return null;
        }
        // Find matching closing quote (handle escaped quotes)
        int pos = firstQuote + 1;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '"') {
                return json.substring(firstQuote + 1, pos);
            }
            if (c == '\\') {
                pos++; // skip escaped char
            }
            pos++;
        }
        return null;
    }

    // ── State capture/restore ────────────────────────────────────────

    @Override
    public Object captureState() {
        var state = new HashMap<String, Object>();
        state.put("poiDurations", new HashMap<>(poiDurations));
        state.put("standbyLocations", new HashMap<>(standbyLocations));
        state.put("standbyLocations40", new HashMap<>(standbyLocations40));
        state.put("mapLoaded", mapLoaded);
        return state;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(Object state) {
        if (!(state instanceof Map)) {
            throw new IllegalArgumentException("Invalid state type for DigitalMapProcessor");
        }

        var stateMap = (Map<String, Object>) state;

        poiDurations.clear();
        Object durationsState = stateMap.get("poiDurations");
        if (durationsState instanceof Map) {
            poiDurations.putAll((Map<String, Integer>) durationsState);
        }

        standbyLocations.clear();
        Object standbyState = stateMap.get("standbyLocations");
        if (standbyState instanceof Map) {
            standbyLocations.putAll((Map<String, String>) standbyState);
        }

        standbyLocations40.clear();
        Object standby40State = stateMap.get("standbyLocations40");
        if (standby40State instanceof Map) {
            standbyLocations40.putAll((Map<String, String>) standby40State);
        }

        Object loadedState = stateMap.get("mapLoaded");
        if (loadedState instanceof Boolean) {
            mapLoaded = (Boolean) loadedState;
        }
    }
}
