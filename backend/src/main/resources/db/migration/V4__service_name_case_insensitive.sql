-- Case-insensitive unique index for service names so names like "Payments" and "payments" collide.

CREATE UNIQUE INDEX service_name_lower_key ON service (lower(name));
