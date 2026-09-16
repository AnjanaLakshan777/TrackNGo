package com.trackngo.feedbackrating.internal.controller;

import com.trackngo.commons.ApiResponse;
import com.trackngo.commons.exception.BusinessException;
import com.trackngo.feedbackrating.api.TripRatingService;
import com.trackngo.feedbackrating.api.dto.TripRatingDto;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ratings")
@RequiredArgsConstructor
public class DriverRatingController {
    private final TripRatingService service;
    private final JdbcTemplate jdbc;

    /**
     * Returns the trip ratings left for the given driver.
     *
     * The DRIVER role proves only that the caller is a driver, not that they
     * are THIS driver, so without the ownership check any signed-in driver
     * could read a colleague's private reviews by changing the id in the path.
     */
    @GetMapping("/driver/{driverId}")
    @PreAuthorize("hasRole('DRIVER') or hasRole('ADMIN')")
    public ApiResponse<List<TripRatingDto>> getRatingsForDriver(@PathVariable Long driverId) {
        assertSelfOrAdmin(driverId);
        return ApiResponse.ok("Fetched", service.getRatingsForDriver(driverId));
    }

    /** Allows the record's owner, or an administrator, and refuses everyone else. */
    private void assertSelfOrAdmin(Long ownerId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null
                || "anonymousUser".equals(auth.getName())) {
            throw new BusinessException("You must be logged in.");
        }
        if (auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            return;
        }
        if (ownerId == null) {
            throw new BusinessException("You can only access your own records.");
        }
        Long callerId;
        try {
            callerId = jdbc.queryForObject(
                    "SELECT user_id FROM `user` WHERE email = ?", Long.class, auth.getName());
        } catch (DataAccessException ex) {
            throw new BusinessException("You must be logged in.");
        }
        if (!ownerId.equals(callerId)) {
            throw new BusinessException("You can only access your own records.");
        }
    }
}
