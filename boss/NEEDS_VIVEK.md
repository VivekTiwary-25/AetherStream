# Things only Vivek (a human) can do

Current public link (quick tunnel, changes if the PC restarts): https://trance-cover-acrobat-submissions.trycloudflare.com
(If it changed: open C:\Users\Arena\aether-tunnel.log and look for the trycloudflare.com line.)

1. [01:12] vimlesh-pc: Vimlesh's Claude was logged out earlier; it is working again now (no action unless it logs out again).
2. [optional] For a link that never changes, create a named Cloudflare tunnel (needs a free Cloudflare account + a domain).
3. [02:22] vimlesh-pc: the swarm could not push a finished task (T26 UI integration) from vimlesh-pc ("push failed").
   On vimlesh-pc open a terminal and run: `swarm fix`  (then `swarm status` should show vimlesh-pc/claude idle, no problem).
   Not blocking: if it fails again the boss moves the task to another worker.
4. [03:45, done 07:10] Boss Opus at its usage limit. Restart the boss on vachana-claude with the restart prompt:
   "You are the boss. Read boss/STATE.md and continue the loop from the original prompt in boss/PROMPT.md."
5. [07:20] utkarsh-pc: a finished task could not be pushed (T89 web coverage). On utkarsh-pc open a terminal and run: `swarm fix`
6. [07:20, resolved 07:29] vimlesh-pc is asleep or offline since ~06:59. Wake it / check power + internet, then run: `swarm up`
   Not blocking: the boss routes its work to other PCs.
7. [08:20] upendra-pc is asleep or offline (no heartbeat 10 min). Wake it / check power + internet, then run: `swarm up`
   Not blocking: its queued tasks move to other PCs automatically.
8. [08:55] vimlesh-pc is asleep or offline again (since ~08:45). Wake it, then run: `swarm up`. Not blocking (work is rerouted).
9. [09:20] (optional, 1 min) Make the laptop boot straight into a ready demo. In PowerShell on vivek-pc:
   `cd D:\projects\AetherStream; powershell -ExecutionPolicy Bypass -File infra\deploy\register-expo-autostart.ps1`
   (undo: same command with `-Unregister`). The boss was not allowed to register a scheduled task itself. Dry run checked OK.
