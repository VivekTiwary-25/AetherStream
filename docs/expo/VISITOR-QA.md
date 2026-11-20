# Visitor Q&A

### 1. Are these real trucks?
No. The six trucks are simulated; they generate position and delivery events that the stream engine processes. The simulator’s speed range tops out at 50 km/h. [docs/expo/DEMO-NEW-UI.md; docs/contracts/01-event-envelope-and-simulator.md]

### 2. What is a partition?
Kafka splits the event stream into six partitions, and each truck’s events use its truck ID as the key. Partitions let workers process separate parts of the stream. [README.md]

### 3. What is a worker?
A worker reads its assigned Kafka partitions, runs the pipeline operators, and writes results to Postgres. The demo runs three workers. [README.md]

### 4. Why is P1 idle when there are only six trucks?
Truck keys are hashed to partitions, so six trucks do not necessarily fill all six partitions: P1 is idle while P4 carries two trucks. Its worker remains alive and can process P1 if more keys arrive. [docs/expo/DEMO-NEW-UI.md]

### 5. What happens when you kill a worker?
It is marked dead at about 2 seconds; recovery from a checkpoint completes at about 4 seconds, and the worker returns with its original partitions. In the recorded test, recovery took about 1.1–1.3 seconds after recovery began; all workers were running again by about 6 seconds. [design/ui-glorious/proof/standby-utkarsh/kill/REPORT.md; docs/expo/CHEATSHEET.md]

### 6. What does a checkpoint save?
An aligned checkpoint saves worker state and Kafka offsets together. The coordinator restores only the latest completed checkpoint, then replays Kafka from its saved offsets. [README.md; DEMO.md]

### 7. Is the output exactly once, and how do you check it?
Input replay is at-least-once, but Postgres window, alert, and dead-letter tables use upserts, making those tables effectively exactly-once. **Compare with reference** replays the checkpointed Kafka range with the same operators in a single process and checks the results against Postgres; it checks that range, not every future failure. [DEMO.md]

### 8. What is a late event?
It arrives after its event-time window has closed according to that partition’s watermark. The engine records it as a `LATE` dead letter instead of adding it to the closed window. [DEMO.md]

### 9. What is a rejected event?
It is a malformed record the engine cannot accept, such as one with a missing speed or a negative speed. Rejected records appear separately from late records in Failed Records. [docs/contracts/01-event-envelope-and-simulator.md; docs/expo/DEMO-NEW-UI.md]

### 10. Is Ask AI really using AI?
When available, Ask AI sends the request through a local bridge to Claude Code; the bridge host must have Claude Code installed and logged in. If AI is unavailable, a saved example is explicitly labelled as not AI-generated, and it can still be reviewed and run. [docs/expo/STANDBY.md; docs/expo/CHEATSHEET.md]

### 11. Does a generated pipeline run without checks?
No. Review the preview and open it in the editor; the pipeline is validated before use, and **Run** starts it only after validation succeeds. [docs/expo/DEMO-NEW-UI.md; DEMO.md]

### 12. Which parts of the city display are real data?
Truck speed and status come from the simulator, but the road paths are drawn for display. The map is not a live GPS route. [docs/expo/DEMO-NEW-UI.md]
