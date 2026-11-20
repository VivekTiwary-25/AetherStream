Analytics windows polish (task t261120-0459-6443): production build (`vite preview`), 1280x860, window crop.
`*-data-*` shots feed the page sample REST/WebSocket responses from a Playwright test harness (no backend was running; nothing is mocked in the app).
`*-empty-*` shots have the API returning 503 and the socket closed (the "Disconnected" empty state).
