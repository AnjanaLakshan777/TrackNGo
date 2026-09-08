
package com.trackngo.tracking.internal.repository;

import com.trackngo.tracking.internal.entity.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RouteRepository extends JpaRepository<Route, Long> {
    boolean existsByRouteCode(String routeCode);
    boolean existsByRouteCodeAndIdNot(String routeCode, Long id);

    @Query("SELECT DISTINCT r FROM Route r LEFT JOIN FETCH r.stops WHERE r.id = :routeId")
    Optional<Route> findByIdWithStops(@Param("routeId") Long routeId);

    /**
     * Every route with its stops already loaded, in one query.
     *
     * Callers that read {@code route.getStops()} for each route — the route
     * geometry lookup and the admin route listing — were using plain
     * {@code findAll()}, which loads the routes and then triggers one extra
     * SELECT per route when the lazy stop collection is first touched. With
     * twenty routes that is twenty-one queries to answer one request, and on a
     * remote database each of those is a full network round trip.
     *
     * Same rows, same entities, one query. Mirrors the fetch-join already used
     * by {@link #findByIdWithStops(Long)}.
     */
    @Query("SELECT DISTINCT r FROM Route r LEFT JOIN FETCH r.stops")
    List<Route> findAllWithStops();
}

