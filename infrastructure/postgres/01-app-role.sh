#!/bin/bash
# Creates the least-privilege application role from brief section 9.
# Runs once, on first initialisation of an empty data volume.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${APP_USER}') THEN
            CREATE ROLE ${APP_USER} LOGIN PASSWORD '${APP_PASSWORD}';
        END IF;
    END
    \$\$;

    GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO ${APP_USER};
    -- Flyway (running as this role) creates each module schema itself.
    GRANT CREATE ON DATABASE ${POSTGRES_DB} TO ${APP_USER};
EOSQL

echo "app role ${APP_USER} ready"
