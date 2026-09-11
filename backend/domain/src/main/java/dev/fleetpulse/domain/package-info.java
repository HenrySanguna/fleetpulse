/**
 * JPA entities, repositories and Flyway migrations for FleetPulse domain data.
 * {@code V1__init.sql} enables postgis and pg_partman; {@code V2}-{@code V4}
 * (change 02-add-fleet-auth) add the Organization/User/Vehicle/Device/
 * MqttCredential model and the Spring Session JDBC schema used to persist
 * dispatcher sessions.
 */
package dev.fleetpulse.domain;
