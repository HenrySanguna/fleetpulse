-- Prod demo service (task 5.1's decision, odd/tasks/prod-demo-simulator.md).
-- Seeds a fixed 10-vehicle fleet + one device each for the existing demo
-- organization (e7de66a7-f8a2-4992-9a57-8d909634609f, from
-- /api/dispatchers/me / dispatcher@fleetpulse.test) so the simulator has
-- real vehicles rows to satisfy the positions/vehicle_state FK to
-- vehicles(id) (V5 migration) -- random UUID.randomUUID() vehicle IDs
-- violated that FK, which is why the live map showed 0 vehicles despite a
-- "Connected" websocket.
--
-- Not a Flyway migration: this is demo-only seed data, run on demand by the
-- one-shot `demo-seed` compose service (docker-compose.prod.yml, profile
-- `demo`), not on every deploy of every environment. Idempotent via
-- ON CONFLICT DO NOTHING on the fixed UUIDs below so reruns (redeploys,
-- restarts) never fail or duplicate rows. The organization row itself is
-- NOT created here -- it already exists in prod (it owns the real
-- dispatcher@fleetpulse.test account) -- only vehicles/devices are seeded.
--
-- These 10 vehicle UUIDs must match SIMULATOR_VEHICLE_IDS in
-- docker-compose.prod.yml's `simulator` service exactly, in the same order,
-- or the simulator will publish telemetry for vehicle IDs that have no
-- matching `vehicles` row and the batch writer will drop it.

INSERT INTO vehicles (id, organization_id, label, created_at) VALUES
    ('8f1cbe99-3d34-49bb-962e-981d28ba4dce', 'e7de66a7-f8a2-4992-9a57-8d909634609f', 'Demo Van 01', now()),
    ('38cf7b2b-c5eb-4a12-bba7-eaa0179ca053', 'e7de66a7-f8a2-4992-9a57-8d909634609f', 'Demo Van 02', now()),
    ('7c8767ea-d90e-4b91-8556-ad3f984f86db', 'e7de66a7-f8a2-4992-9a57-8d909634609f', 'Demo Truck 03', now()),
    ('7d19f0bc-e91f-4aa6-bfe0-7376f06bc832', 'e7de66a7-f8a2-4992-9a57-8d909634609f', 'Demo Truck 04', now()),
    ('802154c2-d7e1-46ee-b963-d800addfe6e8', 'e7de66a7-f8a2-4992-9a57-8d909634609f', 'Demo Sedan 05', now()),
    ('ecd880ad-19bf-4f8a-b291-234668b5f4a2', 'e7de66a7-f8a2-4992-9a57-8d909634609f', 'Demo Sedan 06', now()),
    ('fa9eb9c2-7479-4049-b324-3caf50ed610d', 'e7de66a7-f8a2-4992-9a57-8d909634609f', 'Demo Van 07', now()),
    ('e4399522-ff08-47e5-bb73-51e0e5d7dfa8', 'e7de66a7-f8a2-4992-9a57-8d909634609f', 'Demo Van 08', now()),
    ('4cbe7816-18c3-44b2-ba74-c20078bbab11', 'e7de66a7-f8a2-4992-9a57-8d909634609f', 'Demo Truck 09', now()),
    ('62a73e93-72f7-4e47-b10f-6a658ae88a16', 'e7de66a7-f8a2-4992-9a57-8d909634609f', 'Demo Truck 10', now())
ON CONFLICT (id) DO NOTHING;

-- One device per vehicle. identifier is UNIQUE, so ON CONFLICT targets it
-- rather than id -- either constraint alone is enough for idempotency here
-- since both are fixed, but identifier is the more descriptive intent
-- (matches how devices are provisioned in practice, one physical unit per
-- identifier).
INSERT INTO devices (id, vehicle_id, identifier, created_at) VALUES
    ('b1e80a09-4e6f-484f-9515-c02b25a20967', '8f1cbe99-3d34-49bb-962e-981d28ba4dce', 'demo-device-01', now()),
    ('c25bb68d-81e0-4986-b995-c649989ed724', '38cf7b2b-c5eb-4a12-bba7-eaa0179ca053', 'demo-device-02', now()),
    ('3c503352-e6df-4bd8-9ce9-f05e36541a82', '7c8767ea-d90e-4b91-8556-ad3f984f86db', 'demo-device-03', now()),
    ('239fcd75-fd93-4d0f-bafe-b6aec2723e4d', '7d19f0bc-e91f-4aa6-bfe0-7376f06bc832', 'demo-device-04', now()),
    ('c713f339-7c23-4b34-9f1d-8c2a62bafe1e', '802154c2-d7e1-46ee-b963-d800addfe6e8', 'demo-device-05', now()),
    ('64ed4f42-c60e-4313-909c-7a8239c0794c', 'ecd880ad-19bf-4f8a-b291-234668b5f4a2', 'demo-device-06', now()),
    ('ec24e552-5cdb-4212-aa3d-30ea0a2ae7e3', 'fa9eb9c2-7479-4049-b324-3caf50ed610d', 'demo-device-07', now()),
    ('902a1b81-9024-49bb-8414-640535829ff9', 'e4399522-ff08-47e5-bb73-51e0e5d7dfa8', 'demo-device-08', now()),
    ('a25a95f2-164d-4820-8f12-2690e979ca87', '4cbe7816-18c3-44b2-ba74-c20078bbab11', 'demo-device-09', now()),
    ('f44ddb08-fcd7-41bf-9136-82b7808a63d0', '62a73e93-72f7-4e47-b10f-6a658ae88a16', 'demo-device-10', now())
ON CONFLICT (identifier) DO NOTHING;
