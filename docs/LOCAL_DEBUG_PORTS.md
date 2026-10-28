# Isolated local debugging

Production/TestForge: backend 3080, frontend 5173. Do not restart these during debugging.

Debug: backend 127.0.0.1:3081, frontend localhost:5174. Start backend with `mvn spring-boot:run -Dspring-boot.run.profiles=home,qa-debug`, frontend with `npm run dev:qa-debug`. The profile disables Spring scheduled workers and Quartz auto startup; analysis queue execution and callbacks are disabled. It uses a separate browser/workspace folder. The database configuration is inherited, so this is not a separate data environment: avoid mutations, create test records only in an explicitly isolated test database. For real SDK debugging use a separate database before enabling workers.
