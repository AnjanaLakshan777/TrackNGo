
package com.trackngo.tracking.internal.service;

import com.trackngo.commons.exception.BusinessException;
import com.trackngo.commons.exception.ResourceNotFoundException;
import com.trackngo.commons.booking.BookingDisruptionHandler;
import com.trackngo.tracking.api.RouteService;
import com.trackngo.tracking.api.dto.RouteDto;
import com.trackngo.tracking.internal.entity.Route;
import com.trackngo.tracking.internal.entity.RouteStop;
import com.trackngo.tracking.internal.entity.RouteStopId;
import com.trackngo.tracking.internal.repository.RouteRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/*
  Service implementation for managing bus routes.
  Handles complex logic including route code validation, stop sequence management,
  and parsing/formatting of distance, duration, and fare data.
*/
@Service
@RequiredArgsConstructor
public class RouteServiceImpl implements RouteService {

    /*
      Compiled once rather than on every call. String.replaceAll and
      Pattern.compile inside a method recompile the expression each time, and
      these run for every route in a create or update - pure waste for patterns
      that never change.
    */
    private static final Pattern LEADING_NUMBER = Pattern.compile("([\\d.]+)");
    private static final Pattern HOURS = Pattern.compile("(\\d+)h");
    private static final Pattern MINUTES = Pattern.compile("(\\d+)m");
    private static final Pattern RUPEE_PREFIX = Pattern.compile("(?i)^rs\\.?");
    private static final Pattern NON_NUMERIC = Pattern.compile("[^\\d.]");

    private final RouteRepository repository;
    private final EntityManager entityManager;

    private final BookingDisruptionHandler disruptionHandler;

    /*
      Creates a new bus route.
      Validates that the route code is unique before persisting.
      @param dto The route data to create.
      @return The created RouteDto.
      @throws BusinessException if the route code already exists.
    */
    @Override
    // RouteGeometryService serves the passenger map from an in-memory snapshot of
    // this table, so every write path here has to drop it. Without this, an admin
    // edit would not reach passengers until the cache expired on its own.
    @CacheEvict(value = RouteGeometryService.ROUTE_INDEX_CACHE, allEntries = true)
    @Transactional
    public RouteDto create(RouteDto dto) {
        if (dto.getCode() != null && repository.existsByRouteCode(dto.getCode())) {
            throw new BusinessException("Route code already exists: " + dto.getCode());
        }

        Route entity = new Route();
        applyDtoToEntity(dto, entity);
        return toDto(repository.save(entity));
    }

    /*
      Retrieves a bus route by its ID.
      @param id The unique ID of the route.
      @return The found RouteDto.
      @throws ResourceNotFoundException if the route does not exist.
    */
    @Override
    @Transactional(readOnly = true)
    public RouteDto get(Long id) {
        return toDto(repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Route not found")));
    }

    /*
      Fetches all available bus routes.
      @return A list of all RouteDto records.
    */
    @Override
    @Transactional(readOnly = true)
    public List<RouteDto> getAll() {
        // findAllWithStops rather than findAll: toDto reads entity.getStops() for
        // every route, and with a plain findAll each of those first touches is a
        // separate SELECT against the route_stop table. Same rows, one query.
        return repository.findAllWithStops().stream().map(this::toDto).toList();
    }

    /*
      Updates an existing bus route.
      Ensures the new route code (if changed) is not already in use by another route.
      @param id  The ID of the route to update.
      @param dto The updated route data.
      @return The updated RouteDto.
      @throws ResourceNotFoundException if the route does not exist.
      @throws BusinessException if the new route code already exists.
    */
    @Override
    // RouteGeometryService serves the passenger map from an in-memory snapshot of
    // this table, so every write path here has to drop it. Without this, an admin
    // edit would not reach passengers until the cache expired on its own.
    @CacheEvict(value = RouteGeometryService.ROUTE_INDEX_CACHE, allEntries = true)
    @Transactional
    public RouteDto update(Long id, RouteDto dto) {
        Route entity = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Route not found"));

        if (dto.getCode() != null && repository.existsByRouteCodeAndIdNot(dto.getCode(), id)) {
            throw new BusinessException("Route code already exists: " + dto.getCode());
        }

        if ("Inactive".equalsIgnoreCase(dto.getStatus())) {
            disruptionHandler.cancelFutureBookingsForRoute(id, "the route was made inactive");
        } else if (!Boolean.TRUE.equals(entity.getIsActive()) && "Active".equalsIgnoreCase(dto.getStatus())) {
            disruptionHandler.notifyFutureBookingPassengersRouteRestored(id);
        }

        applyDtoToEntity(dto, entity);
        return toDto(repository.save(entity));
    }

    /*
      Deletes a bus route by its ID.
      @param id The ID of the route to delete.
      @throws ResourceNotFoundException if the route does not exist.
    */
    @Override
    // RouteGeometryService serves the passenger map from an in-memory snapshot of
    // this table, so every write path here has to drop it. Without this, an admin
    // edit would not reach passengers until the cache expired on its own.
    @CacheEvict(value = RouteGeometryService.ROUTE_INDEX_CACHE, allEntries = true)
    @Transactional
    public void delete(Long id) {
        Route entity = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Route not found"));
        disruptionHandler.cancelFutureBookingsForRoute(id, "the route was removed from service");
        // Preserve route/bus/booking history. A route with historical bookings
        // must not be physically deleted because its foreign keys are audited.
        entity.setIsActive(false);
        repository.save(entity);
    }

    /*
      Toggles the active/inactive status of a route.
      @param id The ID of the route to toggle.
      @return The updated RouteDto.
    */
    @Override
    // RouteGeometryService serves the passenger map from an in-memory snapshot of
    // this table, so every write path here has to drop it. Without this, an admin
    // edit would not reach passengers until the cache expired on its own.
    @CacheEvict(value = RouteGeometryService.ROUTE_INDEX_CACHE, allEntries = true)
    @Transactional
    public RouteDto toggleStatus(Long id) {
        Route entity = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Route not found"));
        boolean becomingInactive = Boolean.TRUE.equals(entity.getIsActive());
        if (becomingInactive) {
            disruptionHandler.cancelFutureBookingsForRoute(id, "the route was made inactive");
        } else {
            disruptionHandler.notifyFutureBookingPassengersRouteRestored(id);
        }
        entity.setIsActive(!Boolean.TRUE.equals(entity.getIsActive()));
        return toDto(repository.save(entity));
    }

    /*
      Maps fields from a RouteDto to a Route entity.
      Handles parsing of string-based DTO fields and manages the associated RouteStop collection.
      @param dto    The source DTO.
      @param entity The target entity.
    */
    private void applyDtoToEntity(RouteDto dto, Route entity) {
        entity.setRouteName(dto.getName());
        entity.setRouteCode(dto.getCode());
        entity.setRouteType(dto.getType());
        entity.setEstDistanceDifference(parseDistance(dto.getDistance()));
        entity.setEstimatedTimeDuration(parseDuration(dto.getDuration()));
        entity.setFee(parseFare(dto.getBaseFare()));
        entity.setActiveBuses(dto.getActiveBuses() != null ? dto.getActiveBuses() : 0);
        entity.setIsActive("Active".equalsIgnoreCase(dto.getStatus()));

        List<String> stopNames = dto.getStops();
        if (stopNames != null && !stopNames.isEmpty()) {
            entity.setStartLocation(stopNames.get(0));
            entity.setEndLocation(stopNames.get(stopNames.size() - 1));
        } else {
            entity.setStartLocation("");
            entity.setEndLocation("");
        }

        entity.getStops().clear();
        if (entity.getId() != null) {
            entityManager.flush();
        }
        if (stopNames != null) {
            for (int i = 0; i < stopNames.size(); i++) {
                RouteStop stop = new RouteStop();
                stop.setId(new RouteStopId(entity.getId(), i + 1));
                stop.setRoute(entity);
                stop.setName(stopNames.get(i));
                entity.getStops().add(stop);
            }
        }
    }

    /*
      Converts a Route entity to a RouteDto.
      Formats numeric/temporal fields into human-readable strings for the frontend.
      @param entity The source entity.
      @return The mapped DTO.
    */
    private RouteDto toDto(Route entity) {
        RouteDto dto = new RouteDto();
        dto.setId(entity.getId());
        dto.setName(entity.getRouteName());
        dto.setCode(entity.getRouteCode() != null ? entity.getRouteCode() : "");
        dto.setType(entity.getRouteType() != null ? entity.getRouteType() : "");
        dto.setDistance(formatDistance(entity.getEstDistanceDifference()));
        dto.setDuration(formatDuration(entity.getEstimatedTimeDuration()));
        dto.setActiveBuses(entity.getActiveBuses() != null ? entity.getActiveBuses() : 0);
        dto.setBaseFare(formatFare(entity.getFee()));
        dto.setStatus(Boolean.TRUE.equals(entity.getIsActive()) ? "Active" : "Inactive");

        /*
          Sorted by priority rather than trusting the order the stops happen to
          arrive in. applyDtoToEntity treats the first name as the route's start
          and the last as its end, so the order of this list is meaningful, not
          cosmetic - it must not depend on whether the collection was lazily
          loaded or fetch-joined.
        */
        List<RouteStop> stops = entity.getStops() != null ? entity.getStops() : List.of();
        List<String> stopNames = stops.stream()
                .sorted(Comparator.comparing(
                        (RouteStop stop) -> stop.getId() == null ? null : stop.getId().getPriority(),
                        Comparator.nullsLast(Comparator.<Integer>naturalOrder())))
                .map(RouteStop::getName)
                .collect(Collectors.toCollection(ArrayList::new));
        dto.setStops(stopNames);

        return dto;
    }

    /*
      Parses a distance string (e.g., "12.5 km") into a BigDecimal.
      @param distance The distance string to parse.
      @return The parsed numeric value.
    */
    private BigDecimal parseDistance(String distance) {
        if (distance == null || distance.isBlank()) return BigDecimal.ZERO;
        Matcher m = LEADING_NUMBER.matcher(distance);
        return m.find() ? new BigDecimal(m.group(1)) : BigDecimal.ZERO;
    }

    /*
      Parses a duration string (e.g., "2h 30m") into total minutes.
      @param duration The duration string.
      @return Total minutes as an Integer.
    */
    private Integer parseDuration(String duration) {
        if (duration == null || duration.isBlank()) return 0;
        int total = 0;
        Matcher hm = HOURS.matcher(duration);
        if (hm.find()) total += Integer.parseInt(hm.group(1)) * 60;
        Matcher mm = MINUTES.matcher(duration);
        if (mm.find()) total += Integer.parseInt(mm.group(1));
        return total;
    }

    /*
      Parses a fare string (e.g., "Rs. 150") into a BigDecimal.
      Removes "Rs." prefix and non-numeric characters except decimals.
      @param fare The fare string.
      @return The parsed numeric value.
    */
    private BigDecimal parseFare(String fare) {
        if (fare == null || fare.isBlank()) return BigDecimal.ZERO;
        String cleaned = NON_NUMERIC.matcher(RUPEE_PREFIX.matcher(fare).replaceAll("")).replaceAll("");
        return cleaned.isEmpty() ? BigDecimal.ZERO : new BigDecimal(cleaned);
    }

    /*
      Formats a numeric distance into a string with " km" suffix.
      @param distance The numeric distance.
      @return Formatted string.
    */
    private String formatDistance(BigDecimal distance) {
        if (distance == null) return "0 km";
        return distance.stripTrailingZeros().toPlainString() + " km";
    }

    /*
      Formats total minutes into a "Xh Ym" duration string.
      @param minutes Total minutes.
      @return Formatted duration string.
    */
    private String formatDuration(Integer minutes) {
        if (minutes == null || minutes == 0) return "0h 0m";
        int h = minutes / 60;
        int m = minutes % 60;
        return h + "h " + m + "m";
    }

    /*
      Formats a numeric fee into a string with "Rs." prefix.
      @param fee The numeric fee.
      @return Formatted fare string.
    */
    private String formatFare(BigDecimal fee) {
        if (fee == null) return "Rs.0";
        return "Rs." + fee.stripTrailingZeros().toPlainString();
    }
}

