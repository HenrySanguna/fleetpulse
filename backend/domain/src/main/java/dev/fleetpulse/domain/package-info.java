/**
 * JPA entities, repositories and Flyway migrations for FleetPulse domain data.
 * Entities are added in a later change; this module currently wires Spring
 * Data JPA and Flyway, with {@code V1__init.sql} enabling postgis and
 * pg_partman on an empty database.
 */
package dev.fleetpulse.domain;
