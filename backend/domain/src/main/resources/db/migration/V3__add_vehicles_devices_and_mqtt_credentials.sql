CREATE TABLE vehicles (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    label VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_vehicles_organization_id ON vehicles (organization_id);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES vehicles (id),
    identifier VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_devices_vehicle_id ON devices (vehicle_id);

CREATE TABLE mqtt_credentials (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    device_id UUID REFERENCES devices (id),
    user_id UUID REFERENCES users (id),
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    -- A credential is owned by exactly one of device_id/user_id: device
    -- credentials are vehicle-scoped and long-lived, dispatcher-session
    -- credentials are org-scoped and short-lived (design.md). Mirrors the
    -- invariant MqttCredential's two named factories already enforce in Java.
    CONSTRAINT chk_mqtt_credentials_single_owner CHECK (
        (device_id IS NOT NULL AND user_id IS NULL) OR
        (device_id IS NULL AND user_id IS NOT NULL)
    )
);

CREATE INDEX idx_mqtt_credentials_device_id ON mqtt_credentials (device_id);
CREATE INDEX idx_mqtt_credentials_user_id ON mqtt_credentials (user_id);
