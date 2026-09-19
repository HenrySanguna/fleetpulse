package dev.fleetpulse.processor.eta;

import dev.fleetpulse.geocore.GeoPoint;

// Task 2.3 ("interfaz Java que aisla el calculo para poder sustituirlo por
// un motor de rutas real"): the ONLY seam between EtaRecalculationDispatcher
// and however the actual estimate gets computed. Deliberately pure and
// synchronous -- no JDBC, no MQTT, no Spring context beyond whatever a given
// implementation's own constructor needs -- so a future real routing-engine
// implementation (design.md's documented "primera mejora natural post-MVP")
// can replace SinuosityEtaCalculator by implementing this same interface,
// with zero changes required in EtaRecalculationDispatcher or anything else
// that calls it. `currentPosition`/`destination` carry no dependency on
// vehicle_state or vehicle_destinations rows -- callers resolve those first,
// the same separation TripSegmenter (pure) keeps from JdbcTripReader
// (impl-specific data access).
public interface EtaCalculator {

    EtaEstimate calculate(GeoPoint currentPosition, GeoPoint destination, double recentAverageSpeedKmh);
}
