package com.trackngo.booking.internal.repository;

import com.trackngo.booking.internal.entity.TripBooking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Step 2: The Repository (The Database Translator)
 * 
 * This is an Interface, not a Class! 
 * By simply extending "JpaRepository", Spring Boot automatically writes ALL the 
 * basic SQL code for us in the background. 
 * 
 * We get methods like .save(), .findAll(), and .findById() for free!
 */
@Repository // Tells Spring Boot that this file is responsible for talking to the Database
public interface TripBookingRepository extends JpaRepository<TripBooking, Long> {
    
    // Notice how we don't write any code inside the method?
    // Spring Boot looks at the method name "findByPassengerId" and automatically 
    // generates the SQL: "SELECT * FROM trip_booking WHERE passenger_id = ?"
    List<TripBooking> findByPassengerId(Long passengerId);
    
    // Similarly, this automatically finds all bookings with a specific status
    List<TripBooking> findByBookingStatus(String bookingStatus);

    /**
     * One passenger's pending trip requests that have not had a bus assigned
     * yet - their open drafts - newest first.
     *
     * <p>createBooking used to find these by loading the entire trip_booking
     * table with findAll() and filtering it in Java. That reads every booking
     * ever made in order to find the handful belonging to one passenger, and it
     * gets slower every day the system is used. This asks the database the same
     * question, and the existing idx_passenger (passenger_id, booking_status)
     * index answers it without touching anyone else's rows.
     *
     * <p>LOWER() rather than a plain equals so the comparison stays
     * case-insensitive exactly as the Java filter's equalsIgnoreCase was,
     * instead of depending on the column's collation. The passenger_id predicate
     * is what selects the index, so this costs nothing.
     */
    @Query("""
            SELECT tb FROM TripBooking tb
            WHERE tb.passengerId = :passengerId
              AND LOWER(tb.bookingStatus) = 'pending'
              AND tb.busId IS NULL
            ORDER BY tb.id DESC
            """)
    List<TripBooking> findOpenDraftsByPassenger(@Param("passengerId") Long passengerId);
}
