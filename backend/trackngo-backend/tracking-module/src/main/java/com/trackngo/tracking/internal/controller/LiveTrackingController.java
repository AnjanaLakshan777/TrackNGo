package com.trackngo.tracking.internal.controller;

import com.fasterxml.jackson.databind.ObjectMapper; // Jackson JSON library
import com.trackngo.commons.ApiResponse;
import com.trackngo.tracking.api.dto.BusDriverDto;
import com.trackngo.tracking.api.dto.LiveBusLocationDto;
import com.trackngo.tracking.api.dto.RouteGeometryDto;
import com.trackngo.tracking.internal.service.BusDriverLookupService;
import com.trackngo.tracking.internal.service.BusLocationRecorder;
import com.trackngo.tracking.internal.service.LiveLocationQualityService;
import com.trackngo.tracking.internal.service.RouteGeometryService;
import com.trackngo.tracking.internal.websocket.TrackingWebSocketHandler;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.TextMessage;

@Slf4j
@RestController
@RequestMapping("/api/tracking")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LiveTrackingController {

    private final TrackingWebSocketHandler trackingWebSocketHandler;
    private final ObjectMapper objectMapper;
    private final LiveLocationQualityService liveLocationQualityService;
    private final BusDriverLookupService busDriverLookupService;
    private final BusLocationRecorder busLocationRecorder;
    private final RouteGeometryService routeGeometryService;

    /*
      POST /api/tracking/live-location
      Called by a driver device to publish its current GPS fix.

      Fixes that are impossible or too imprecise to be trusted are dropped here
      rather than forwarded to passengers - see LiveLocationQualityService. A
      dropped fix is still a successful request from the driver app's point of
      view (nothing went wrong with the call), so it answers 200 with
      success=false and the last known good position.
    */
    @PostMapping("/live-location")
    @Transactional
    public ResponseEntity<ApiResponse<LiveBusLocationDto>> publishBusLocation(
            @Valid @RequestBody LiveBusLocationDto dto) {

        LiveLocationQualityService.Result result =
                liveLocationQualityService.submit(dto, System.currentTimeMillis());

        if (!result.isAccepted()) {
            return ResponseEntity.ok(ApiResponse.fail(result.getReason(), result.getLocation()));
        }

        LiveBusLocationDto accepted = result.getLocation();

        // Persist the fix so readers outside this process - the AI assistant asking
        // where a bus is - see the same position the map shows, instead of whatever
        // was last left in the table. Recording never blocks the broadcast.
        busLocationRecorder.record(accepted);

        // Broadcast via WebSocket to all connected clients
        try {
            String json = objectMapper.writeValueAsString(accepted);
            trackingWebSocketHandler.broadcast(new TextMessage(json));
        } catch (Exception e) {
            log.error("Failed to broadcast bus location for {}", accepted.getBusNumber(), e);
        }

        return ResponseEntity.ok(ApiResponse.ok(result.getReason(), accepted));
    }

    /*
      GET /api/tracking/live-location/{busNumber}
      Retrieve the last known good location of a bus, annotated with how old the
      fix is so the caller can decide whether to show it as live.
    */
    @GetMapping("/live-location/{busNumber}")
    public ResponseEntity<ApiResponse<LiveBusLocationDto>> getLatestBusLocation(
            @PathVariable String busNumber) {

        return liveLocationQualityService.latest(busNumber, System.currentTimeMillis())
                .map(location -> ResponseEntity.ok(ApiResponse.ok("Latest bus location", location)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.ok("No location available", null)));
    }

    /*
      GET /api/tracking/buses/{busNumber}/driver
      Returns the driver assigned to a bus so the passenger tracking that bus can
      open a chat with them directly.

      A bus with no driver assigned is not an error - the caller simply has
      nobody to message - so this answers 200 with a null payload, matching the
      live-location endpoint above.
    */
    @GetMapping("/buses/{busNumber}/driver")
    public ResponseEntity<ApiResponse<BusDriverDto>> getBusDriver(@PathVariable String busNumber) {
        return busDriverLookupService.findDriverForBus(busNumber)
                .map(driver -> ResponseEntity.ok(ApiResponse.ok("Bus driver", driver)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.ok("No driver assigned", null)));
    }

    /*
      GET /api/tracking/route-geometry?start={startLocation}&end={endLocation}
      Returns route stops with coordinates for drawing polylines on the map.
    */
    @GetMapping("/route-geometry")
    public ResponseEntity<ApiResponse<RouteGeometryDto>> getRouteGeometry(
            @RequestParam String start,
            @RequestParam String end) {

        // Matching rules are unchanged - prefer a route whose own endpoints are
        // the two places, then fall back to any route that visits both in the
        // right direction, because trip bookings are often between two stops in
        // the middle of a route. What changed is where the data comes from: one
        // fetch-join query behind a cache, rather than loading every route and
        // then lazily loading each one's stops.
        return ResponseEntity.ok(routeGeometryService.findGeometry(start, end)
                .map(geometry -> ApiResponse.ok("Route geometry", geometry))
                .orElseGet(() -> ApiResponse.ok("No route found", null)));
    }

    /**
     * GET /api/tracking/routes/{routeId}/geometry
     * Returns route stops with coordinates for a specific route id.
     */
    @GetMapping("/routes/{routeId}/geometry")
    public ResponseEntity<ApiResponse<RouteGeometryDto>> getRouteGeometryById(
            @PathVariable Long routeId) {

        return ResponseEntity.ok(routeGeometryService.findGeometryById(routeId)
                .map(geometry -> ApiResponse.ok("Route geometry", geometry))
                .orElseGet(() -> ApiResponse.ok("No route found", null)));
    }
}
