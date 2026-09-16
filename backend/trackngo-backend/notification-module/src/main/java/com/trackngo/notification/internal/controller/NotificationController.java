package com.trackngo.notification.internal.controller;

import com.trackngo.notification.api.NotificationService;
import com.trackngo.notification.api.dto.NotificationDto;
import com.trackngo.commons.ApiResponse;
import com.trackngo.commons.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Every route here used to be reachable with no credentials, including the
 * per-user delete endpoints that clear an entire history and the create endpoint
 * that could fabricate a notification for anyone.
 *
 * The feeds are keyed by passenger, corporate, driver and admin id - all of which
 * are user.user_id values - so one ownership check serves all four. Routes keyed
 * by notification id resolve the row's owner first. Admin broadcasts are
 * unaffected: those live on AdminBroadcastController under /api/admin/notifications.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService service;
    private final JdbcTemplate jdbc;

    /** Creating a notification for an arbitrary user is an administrative act. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<NotificationDto> create(@Valid @RequestBody NotificationDto dto) {
        return ApiResponse.ok("Created", service.create(dto));
    }

    @GetMapping("/{id}")
    public ApiResponse<NotificationDto> get(@PathVariable Long id) {
        NotificationDto dto = service.get(id);
        assertSelfOrAdmin(ownerOf(dto));
        return ApiResponse.ok("Fetched", dto);
    }

    @GetMapping
    public ApiResponse<List<NotificationDto>> getAll(
        @RequestParam(required = false) Long userId,
        @RequestParam(required = false) String type
    ) {
        if (userId != null) {
            assertSelfOrAdmin(userId);
            return ApiResponse.ok("Fetched", service.getPassengerNotifications(userId, type));
        }
        // No user named: this is the whole table, which only an admin may read.
        assertAdmin();
        return ApiResponse.ok("Fetched", service.getAll());
    }

    @GetMapping("/passenger/{passengerId}")
    public ApiResponse<List<NotificationDto>> getPassengerNotifications(
        @PathVariable Long passengerId,
        @RequestParam(required = false) String type
    ) {
        assertSelfOrAdmin(passengerId);
        return ApiResponse.ok("Fetched", service.getPassengerNotifications(passengerId, type));
    }

    @GetMapping("/corporate/{corporateUserId}")
    public ApiResponse<List<NotificationDto>> getCorporateNotifications(
        @PathVariable Long corporateUserId,
        @RequestParam(required = false) String type
    ) {
        assertSelfOrAdmin(corporateUserId);
        return ApiResponse.ok("Fetched", service.getCorporateNotifications(corporateUserId, type));
    }

    @GetMapping("/driver/{driverId}")
    public ApiResponse<List<NotificationDto>> getDriverNotifications(
        @PathVariable Long driverId,
        @RequestParam(required = false) String type
    ) {
        assertSelfOrAdmin(driverId);
        return ApiResponse.ok("Fetched", service.getDriverNotifications(driverId, type));
    }

    @GetMapping("/admin/{adminId}")
    public ApiResponse<List<NotificationDto>> getAdminNotifications(
        @PathVariable Long adminId,
        @RequestParam(required = false) String type
    ) {
        assertSelfOrAdmin(adminId);
        return ApiResponse.ok("Fetched", service.getAdminNotifications(adminId, type));
    }

    /** Rewriting a notification's contents is an administrative act. */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<NotificationDto> update(@PathVariable Long id, @Valid @RequestBody NotificationDto dto) {
        return ApiResponse.ok("Updated", service.update(id, dto));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<NotificationDto> markRead(@PathVariable Long id) {
        assertSelfOrAdmin(ownerOf(service.get(id)));
        return ApiResponse.ok("Updated", service.markRead(id));
    }

    @PutMapping("/passenger/{passengerId}/read")
    public ApiResponse<Void> markPassengerNotificationsRead(@PathVariable Long passengerId) {
        assertSelfOrAdmin(passengerId);
        service.markPassengerNotificationsRead(passengerId);
        return ApiResponse.ok("Updated", null);
    }

    @PutMapping("/corporate/{corporateUserId}/read")
    public ApiResponse<Void> markCorporateNotificationsRead(@PathVariable Long corporateUserId) {
        assertSelfOrAdmin(corporateUserId);
        service.markCorporateNotificationsRead(corporateUserId);
        return ApiResponse.ok("Updated", null);
    }

    @PutMapping("/driver/{driverId}/read")
    public ApiResponse<Void> markDriverNotificationsRead(@PathVariable Long driverId) {
        assertSelfOrAdmin(driverId);
        service.markDriverNotificationsRead(driverId);
        return ApiResponse.ok("Updated", null);
    }

    @PutMapping("/admin/{adminId}/read")
    public ApiResponse<Void> markAdminNotificationsRead(@PathVariable Long adminId) {
        assertSelfOrAdmin(adminId);
        service.markAdminNotificationsRead(adminId);
        return ApiResponse.ok("Updated", null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        assertSelfOrAdmin(ownerOf(service.get(id)));
        service.delete(id);
        return ApiResponse.ok("Deleted", null);
    }

    @DeleteMapping("/passenger/{passengerId}")
    public ApiResponse<Void> deletePassengerNotifications(@PathVariable Long passengerId) {
        assertSelfOrAdmin(passengerId);
        service.deletePassengerNotifications(passengerId);
        return ApiResponse.ok("Deleted", null);
    }

    @DeleteMapping("/corporate/{corporateUserId}")
    public ApiResponse<Void> deleteCorporateNotifications(@PathVariable Long corporateUserId) {
        assertSelfOrAdmin(corporateUserId);
        service.deleteCorporateNotifications(corporateUserId);
        return ApiResponse.ok("Deleted", null);
    }

    @DeleteMapping("/driver/{driverId}")
    public ApiResponse<Void> deleteDriverNotifications(@PathVariable Long driverId) {
        assertSelfOrAdmin(driverId);
        service.deleteDriverNotifications(driverId);
        return ApiResponse.ok("Deleted", null);
    }

    @DeleteMapping("/admin/{adminId}")
    public ApiResponse<Void> deleteAdminNotifications(@PathVariable Long adminId) {
        assertSelfOrAdmin(adminId);
        service.deleteAdminNotifications(adminId);
        return ApiResponse.ok("Deleted", null);
    }

    /** A notification carries exactly one owner id; this returns whichever it is. */
    private Long ownerOf(NotificationDto dto) {
        if (dto == null) {
            return null;
        }
        if (dto.getPassengerId() != null) return dto.getPassengerId();
        if (dto.getDriverId() != null) return dto.getDriverId();
        if (dto.getCorporateUserId() != null) return dto.getCorporateUserId();
        return dto.getAdminId();
    }

    private void assertAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new BusinessException("Administrator access is required.");
        }
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
