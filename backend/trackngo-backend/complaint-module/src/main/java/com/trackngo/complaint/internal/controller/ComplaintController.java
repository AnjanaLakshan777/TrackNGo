package com.trackngo.complaint.internal.controller;

import com.trackngo.complaint.api.ComplaintService;
import com.trackngo.complaint.api.dto.ComplaintDto;
import com.trackngo.commons.ApiResponse;
import com.trackngo.commons.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/complaints")
@RequiredArgsConstructor
public class ComplaintController {
    private final ComplaintService service;
    private final JdbcTemplate jdbc;

    /**
     * Resolves the caller from their authenticated identity, and nothing else.
     *
     * This used to fall back to a userId supplied in the query string whenever
     * authentication was absent, so an anonymous caller could read any user's
     * complaints - and file one in their name - simply by naming them. The
     * parameter is still accepted so existing clients keep working, but it is
     * no longer treated as proof of identity.
     */
    private String resolveEmail(Authentication authentication, Long userId) {
        if (authentication != null
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null
                && !authentication.getName().isBlank()) {
            return authentication.getName();
        }
        throw new BusinessException("You must be logged in.");
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

    /** Creates a new complaint for the authenticated passenger. */
    @PostMapping
    public ApiResponse<ComplaintDto> create(
            Authentication authentication,
            @RequestParam(required = false) Long userId,
            @Valid @RequestBody ComplaintDto dto) {
        String email = resolveEmail(authentication, userId);
        return ApiResponse.ok("Created", service.create(email, dto));
    }

    /** Returns complaints submitted by the current passenger. */
    @GetMapping("/mine")
    public ApiResponse<List<ComplaintDto>> getMine(
            Authentication authentication,
            @RequestParam(required = false) Long userId) {
        String email = resolveEmail(authentication, userId);
        return ApiResponse.ok("Fetched", service.getMine(email));
    }

    /**
     * Returns the complaints filed against the given driver.
     *
     * The DRIVER role proves only that the caller is a driver, not that they
     * are THIS driver, so without the ownership check any signed-in driver
     * could read a colleague's complaints by changing the id in the path.
     */
    @GetMapping("/driver/{driverId:\\d+}")
    @PreAuthorize("hasRole('DRIVER') or hasRole('ADMIN')")
    public ApiResponse<List<ComplaintDto>> getForDriver(@PathVariable Long driverId) {
        assertSelfOrAdmin(driverId);
        return ApiResponse.ok("Fetched", service.getForDriver(driverId));
    }

    /** Returns a single complaint for admin users. */
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ComplaintDto> get(@PathVariable Long id) {
        return ApiResponse.ok("Fetched", service.get(id));
    }

    /** Returns every complaint for admin users. */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<ComplaintDto>> getAll() {
        return ApiResponse.ok("Fetched", service.getAll());
    }

    /** Updates a complaint for admin users. */
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ComplaintDto> update(@PathVariable Long id, @Valid @RequestBody ComplaintDto dto) {
        return ApiResponse.ok("Updated", service.update(id, dto));
    }

    /** Deletes a complaint for admin users. */
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok("Deleted", null);
    }
}
