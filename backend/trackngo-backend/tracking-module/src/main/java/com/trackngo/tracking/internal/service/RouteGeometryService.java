package com.trackngo.tracking.internal.service;

import com.trackngo.tracking.api.dto.RouteGeometryDto;
import com.trackngo.tracking.api.dto.RouteStopDto;
import com.trackngo.tracking.internal.entity.Route;
import com.trackngo.tracking.internal.entity.RouteStop;
import com.trackngo.tracking.internal.repository.RouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Answers "which route runs between these two places, and where are its stops"
 * from an in-memory snapshot of the route table.
 *
 * <p>The passenger map asks this on every trip view. It used to be served by
 * loading every {@code Route} row and then touching each one's lazy stop
 * collection, which issued one further SELECT per route — twenty-one queries to
 * answer one request, each a full network round trip to a remote database.
 *
 * <p>Two things changed and neither alters the answer:
 * <ul>
 *   <li>the routes and their stops are read in a single fetch-join query, and</li>
 *   <li>the result is held as an immutable snapshot in a Caffeine cache, because
 *       routes change when an administrator edits them and not otherwise.</li>
 * </ul>
 *
 * <p>Correctness of the cache is not left to its TTL: every write path in
 * {@link RouteServiceImpl} evicts {@link #ROUTE_INDEX_CACHE}, so an edit is
 * visible on the very next request. The TTL is only a backstop against a change
 * made directly in the database, outside the application.
 *
 * <p>The matching rules — normalise, prefer a route whose endpoints match, then
 * fall back to any route that visits both places in the right order — are
 * carried over exactly as they were, including the order routes are considered
 * in, which is by ascending route id.
 */
@Service
@RequiredArgsConstructor
public class RouteGeometryService {

    /**
     * Cache name shared with the {@code @CacheEvict} annotations on
     * {@link RouteServiceImpl}'s write paths. Changing it here means changing it
     * there too, or edits stop being visible.
     */
    public static final String ROUTE_INDEX_CACHE = "routeIndex";

    /*
      Pre-compiled because this runs once per stop per lookup. String.replaceAll
      recompiles the pattern on every call, which is pure waste on a hot path.
    */
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");

    private final RouteRepository routeRepository;

    /**
     * One stop on a route, flattened to plain values.
     *
     * <p>Snapshots deliberately hold no JPA entities. A cached entity would be a
     * detached, mutable object shared between every concurrent request, and the
     * {@code Route} entity is a Lombok {@code @Data} class with setters on every
     * field — exactly the shape that turns a shared cache into a data race.
     */
    public record StopSnapshot(
            String name,
            String normalizedName,
            Double latitude,
            Double longitude,
            Integer priority,
            Double distanceFromStart,
            Integer estimatedArrivalMins) {
    }

    /** One route with its stops, in priority order. */
    public record RouteSnapshot(
            Long id,
            String routeName,
            String startLocation,
            String endLocation,
            String normalizedStart,
            String normalizedEnd,
            List<StopSnapshot> stops) {
    }

    /**
     * Every route with its stops, ordered by route id.
     *
     * <p>Ordering matters: both lookups below take the <em>first</em> matching
     * route, so the order routes are considered in is part of the answer. The
     * previous {@code findAll()} left it to the database, which for an InnoDB
     * full scan means primary key order; sorting explicitly pins that down
     * rather than depending on it.
     */
    @Cacheable(ROUTE_INDEX_CACHE)
    public List<RouteSnapshot> index() {
        List<Route> routes = routeRepository.findAllWithStops();
        List<RouteSnapshot> snapshots = new ArrayList<>(routes.size());
        for (Route route : routes) {
            snapshots.add(toSnapshot(route));
        }
        snapshots.sort(Comparator.comparing(RouteSnapshot::id,
                Comparator.nullsLast(Comparator.<Long>naturalOrder())));
        return List.copyOf(snapshots);
    }

    /**
     * Finds the route to draw between two places.
     *
     * <p>Prefers a route whose own endpoints are the two places. Failing that,
     * accepts any route that visits both as intermediate stops in the right
     * direction — trip bookings are often between two stops in the middle of a
     * route rather than its termini.
     */
    public Optional<RouteGeometryDto> findGeometry(String start, String end) {
        String wantedStart = normalize(start);
        String wantedEnd = normalize(end);
        if (wantedStart == null || wantedEnd == null) {
            return Optional.empty();
        }

        List<RouteSnapshot> index = index();

        for (RouteSnapshot route : index) {
            if (wantedStart.equals(route.normalizedStart()) && wantedEnd.equals(route.normalizedEnd())) {
                return Optional.of(toGeometry(route));
            }
        }
        for (RouteSnapshot route : index) {
            if (visitsInOrder(route, wantedStart, wantedEnd)) {
                return Optional.of(toGeometry(route));
            }
        }
        return Optional.empty();
    }

    /** Finds one route's geometry by id, from the same snapshot. */
    public Optional<RouteGeometryDto> findGeometryById(Long routeId) {
        if (routeId == null) {
            return Optional.empty();
        }
        return index().stream()
                .filter(route -> routeId.equals(route.id()))
                .findFirst()
                .map(this::toGeometry);
    }

    /*
      True when the route stops at both places and reaches `start` before `end`.
      Uses the first occurrence of each name, matching the original loop: a route
      that visits the same stop twice is judged on its earliest visit.
    */
    private boolean visitsInOrder(RouteSnapshot route, String start, String end) {
        int startIndex = -1;
        int endIndex = -1;
        List<StopSnapshot> stops = route.stops();
        for (int i = 0; i < stops.size(); i++) {
            String name = stops.get(i).normalizedName();
            if (name == null) continue;
            if (startIndex < 0 && name.equals(start)) startIndex = i;
            if (endIndex < 0 && name.equals(end)) endIndex = i;
        }
        return startIndex >= 0 && endIndex > startIndex;
    }

    private RouteSnapshot toSnapshot(Route route) {
        List<RouteStop> stops = route.getStops() == null ? List.of() : route.getStops();

        /*
          Sorted defensively by priority rather than trusting the order the fetch
          join happened to return. The ordered-stops rule above reads position in
          this list as "which stop comes first on the route", so getting the order
          wrong would silently change which routes match.
        */
        List<StopSnapshot> ordered = stops.stream()
                .sorted(Comparator.comparing(
                        (RouteStop stop) -> stop.getId() == null ? null : stop.getId().getPriority(),
                        Comparator.nullsLast(Comparator.<Integer>naturalOrder())))
                .map(stop -> new StopSnapshot(
                        stop.getName(),
                        normalize(stop.getName()),
                        toDouble(stop.getLatitude()),
                        toDouble(stop.getLongitude()),
                        stop.getId() == null ? null : stop.getId().getPriority(),
                        toDouble(stop.getDistanceFromStart()),
                        stop.getEstimatedArrivalMins()))
                .toList();

        return new RouteSnapshot(
                route.getId(),
                route.getRouteName(),
                route.getStartLocation(),
                route.getEndLocation(),
                normalize(route.getStartLocation()),
                normalize(route.getEndLocation()),
                ordered);
    }

    /*
      A fresh DTO per request. The snapshot itself is shared by every caller, so
      handing out the same mutable DTO would let one response's serialisation
      race another's.
    */
    private RouteGeometryDto toGeometry(RouteSnapshot route) {
        RouteGeometryDto geometry = new RouteGeometryDto();
        geometry.setRouteId(route.id());
        geometry.setRouteName(route.routeName());
        geometry.setStartLocation(route.startLocation());
        geometry.setEndLocation(route.endLocation());

        List<RouteStopDto> stops = new ArrayList<>(route.stops().size());
        for (StopSnapshot stop : route.stops()) {
            RouteStopDto dto = new RouteStopDto();
            dto.setName(stop.name());
            dto.setLatitude(stop.latitude());
            dto.setLongitude(stop.longitude());
            dto.setPriority(stop.priority());
            dto.setDistanceFromStart(stop.distanceFromStart());
            dto.setEstimatedArrivalMins(stop.estimatedArrivalMins());
            stops.add(dto);
        }
        geometry.setStops(stops);
        return geometry;
    }

    private static Double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }

    /*
      Kept byte-for-byte equivalent to the comparison the controller used to do
      inline: trim, lower-case, then drop everything that is not a letter or a
      digit, so "Colombo Fort" and "colombo-fort" are the same place.
    */
    private static String normalize(String value) {
        if (value == null) return null;
        return NON_ALPHANUMERIC.matcher(value.trim().toLowerCase()).replaceAll("");
    }
}
