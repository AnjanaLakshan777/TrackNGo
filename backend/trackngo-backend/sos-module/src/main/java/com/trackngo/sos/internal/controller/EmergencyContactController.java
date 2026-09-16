package com.trackngo.sos.internal.controller;

import com.trackngo.commons.ApiResponse;
import com.trackngo.commons.exception.BusinessException;
import com.trackngo.commons.exception.ResourceNotFoundException;
import com.trackngo.sos.api.EmergencyContactService;
import com.trackngo.sos.api.dto.CreateEmergencyContactRequest;
import com.trackngo.sos.api.dto.EmergencyContactDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Emergency contacts are next-of-kin names and phone numbers, and they belong to
 * people who never used this app themselves. Every method therefore checks that
 * the caller owns the record: previously the owner was taken from the request, so
 * anyone could read, add to, or delete anyone else's contact list.
 */
@RestController
@RequestMapping("/api/emergency-contacts")
@RequiredArgsConstructor
public class EmergencyContactController {
    private final EmergencyContactService service;
    private final JdbcTemplate jdbc;

    @GetMapping
    public ApiResponse<List<EmergencyContactDto>> getContacts(
            @RequestParam("ownerId") Long ownerId,
            @RequestParam("ownerType") String ownerType) {
        assertSelfOrAdmin(ownerId);
        return ApiResponse.ok("Fetched", service.getContactsByOwner(ownerId, ownerType.toLowerCase()));
    }

    @PostMapping
    public ApiResponse<EmergencyContactDto> addContact(
            @Valid @RequestBody CreateEmergencyContactRequest request) {
        assertSelfOrAdmin(request.getOwnerId());
        return ApiResponse.ok("Created", service.addContact(request));
    }

    @DeleteMapping("/{contactId}")
    public ApiResponse<Void> deleteContact(@PathVariable("contactId") Long contactId) {
        // The contact is addressed by its own id, so the owner has to be looked up
        // before the caller can be checked against it.
        assertSelfOrAdmin(ownerOf(contactId));
        service.deleteContact(contactId);
        return ApiResponse.ok("Deleted");
    }

    /** Resolves which user a contact row belongs to, or 404s if there is no such row. */
    private Long ownerOf(Long contactId) {
        Long ownerId;
        try {
            ownerId = jdbc.queryForObject(
                    "SELECT owner_id FROM emergency_contact WHERE contact_id = ?", Long.class, contactId);
        } catch (DataAccessException ex) {
            throw new ResourceNotFoundException("Emergency contact not found");
        }
        if (ownerId == null) {
            throw new ResourceNotFoundException("Emergency contact not found");
        }
        return ownerId;
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
