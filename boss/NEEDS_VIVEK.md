# Things only Vivek (a human) can do

Current public link (quick tunnel, changes if the PC restarts): https://trance-cover-acrobat-submissions.trycloudflare.com
(If it changed: open C:\Users\Arena\aether-tunnel.log and look for the trycloudflare.com line.)

1. [01:12] vimlesh-pc: Vimlesh's Claude was logged out earlier; it is working again now (no action unless it logs out again).
2. [optional] For a link that never changes, create a named Cloudflare tunnel (needs a free Cloudflare account + a domain).
3. [02:22] vimlesh-pc: the swarm could not push a finished task (T26 UI integration) from vimlesh-pc ("push failed").
   On vimlesh-pc open a terminal and run: `swarm fix`  (then `swarm status` should show vimlesh-pc/claude idle, no problem).
   Not blocking: if it fails again the boss moves the task to another worker.
