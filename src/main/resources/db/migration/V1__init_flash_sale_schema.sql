CREATE TABLE inventory_items (
    id UUID PRIMARY KEY,
    sku VARCHAR(64) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    total_stock INT NOT NULL CHECK (total_stock >= 0),
    available_stock INT NOT NULL CHECK (available_stock >= 0 AND available_stock <= total_stock),
    price_minor BIGINT NOT NULL CHECK (price_minor >= 0),
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_reservations_user_id_sku ON reservations (user_id, sku);
CREATE INDEX idx_reservations_status_expires_at ON reservations (status, expires_at);

CREATE TABLE wallet_accounts (
    id UUID PRIMARY KEY,
    user_id VARCHAR(64) UNIQUE NOT NULL,
    balance_minor BIGINT NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'SEK',
    version INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    reservation_id UUID REFERENCES reservations(id),
    user_id VARCHAR(64) NOT NULL,
    total_amount_minor BIGINT NOT NULL CHECK (total_amount_minor >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'SEK',
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
