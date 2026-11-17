# Measuring the web app

How long the main thread is blocked while someone uses the app with a lived-in amount of data.

1. Build the server and the web app: `./gradlew :server:installDist` and `npm run build` in `web/`.
2. Start a fresh server on port 8080 with `HUSH_WEB_DIR=web/dist`, and set it up (POST `/api/v1/setup` with the code
   in `data/setup-code.txt`).
3. Seed it: `HUSH_PERF_URL=http://127.0.0.1:8080 HUSH_PERF_INVITE=<data>/first-invite.txt HUSH_PERF_OUT=<dir>/seed.env
   ./gradlew :shared:client:test --tests 'xyz.aezdek.hush.client.PerfSeed' --rerun`. It keeps running, sending a
   message a second while `<dir>/perf-live` exists, until `<dir>/perf-stop` does.
4. Measure: `PERF_DIR=<dir> node web/perf/measure.cjs <dir>/result.json`. Each step reports its long tasks (main-thread
   blocks over 50 ms) and saves a CPU profile next to the results. Build with `npx vite build --minify false` to read
   function names in the profiles.
