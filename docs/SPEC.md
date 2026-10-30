# MineComputers: Spec (fresh start, v13, complete)

Status: updated with Ralfs's answers. The limits and safety section (10) was approved by Ralfs.

## 1. Overview
- Mod name: **MineComputers**
- Platform: **NeoForge 1.21.1**
- The mod itself is written in **Java**.
- In-game programs are written in **JavaScript**.
- Must be compatible with **Create: Aeronautics** (and Create in general; the Servo drives Create shafts).
- Must **work on a dedicated multiplayer server**.
- Crafting recipes: **later**.

## 2. Blocks

| Block | What it does |
|---|---|
| **Brain** | Where the code sits and runs. Needs **no FE**. |
| **Data Cable** | Links parts together; transports data/commands only. |
| **Power Cable** | Transports **FE only**. |
| **Power Data Cable** | Transports **FE and data/commands** on the same line. |
| **Servo** | Create-compatible. Turns a shaft (in the middle of the block) to a programmed angle. Angle, speed and degrees are all programmable; degrees are **unlimited**. **Holds its angle until the code sets a new one** (for example back to 0). Uses **FE only**. **Max load it can turn: 2k SU.** See 5. |
| **Screen** | Displays whatever you program: text, pixels/graphics, images, or video. **Multi-block like Create's radars:** sizes **1x1, 2x2, 3x3, 4x4** only. **1K per block.** **No sound** (sound comes from the Speaker). See 6. |
| **Speaker** | Plays what you program: **dragged-in sound files** or **URLs**. Can **sync with a Screen** to play movies. Uses **5 FE per second, always**. Heard like a note block or music disc. See 6. |
| **Remote Gateway** | The network's remote entry point. A laptop with the Remote Access upgrade can link to it and edit the system from up to **50 blocks** away. |
| **Redstone Link** | Sends a redstone signal from a command, **Create style**. **8 links per block.** You open the block and **add a signal to each link** (Create-style frequency), so they pair with Create's own Redstone Links. |

## 3. Items

| Item | What it does |
|---|---|
| **Laptop** | The tool for coding a brain. See 4. |
| **Remote Access (laptop upgrade)** | Installed in a laptop. Lets it reach a system's Remote Gateway. |
| **Link Transmitter** | Put into a laptop to link it to a system. **One system per laptop.** |
| **Cipher** ("shifer") | Locks a system so that **only you** can access it. Other players can try to **hack** it with a laptop through a mini game. See 8. |

## 4. How the laptop works
Two ways to edit a brain:

1. **Direct:** place the laptop on a cable or on a brain to code the brain.
2. **Remote:** a laptop with the **Remote Access** upgrade installed can reach a system that has a **Remote Gateway**. Link the two (with the Link Transmitter) and edit **without placing the laptop**, within **50 blocks** of the gateway.

A laptop links to **one system** at a time (one Link Transmitter, one system).

## 5. Power (FE) and stress (SU)
- The Brain draws **no FE**.
- The Servo costs **1 FE per 3 seconds** while idle (not moving).
- While **rotating**, the Servo draws what it needs: **faster = more FE, longer = more FE**.
- The Servo's **max load is 2k SU**: it can't turn more than 2,000 SU of stress.
- The Speaker uses **5 FE per second, always** (whether it is playing or doing nothing).
- Power Cable / Power Data Cable deliver the FE.

## 6. Screens and speakers: images, video, sound
**Screen**
- Resolution: **1K per block** (1024x1024 pixels per block). So **1x1 = 1K, 2x2 = 2K, 3x3 = 3K, 4x4 = 4K**.
- Frame rate: **60 FPS max**.
- **No sound** on the screen.
- You add images and videos by **file** or **URL**.
- For a file: while the **laptop GUI is open**, **drag the file** from your file manager into the Minecraft client.
- For a URL: enter it in the laptop.

**Supported formats:** all the modern ones, on **every platform including Linux**: images (PNG, JPG, WebP), audio (OGG and other common formats), and video (MP4, WebM, and recordings made on Linux, which are often WebM or MKV). (confirmed by Ralfs)
- **Implementation note:** Minecraft has no built-in video decoder, so this needs a bundled decoder library (FFmpeg-based) with natives for Windows, macOS and Linux. To be researched when we get to the screen.

**Speaker**
- Plays **URLs** or **dragged-in sound files** (dragged in the same way as screen files).
- Can **sync with a Screen** so a movie's video and sound play together.
- Players hear it **like a note block or music disc** (normal in-world sound that gets quieter with distance).

**Design notes**
- A 4x4 screen is 4096x4096. Sending raw pixels from the server every frame would be far too heavy. So scripts send **draw commands** (text, shapes, image/video placement), and **clients render them and play video locally**. The pixel buffer never goes over the network. Also add a client setting to cap render/video resolution for weak GPUs.
- For **sync**, the server sends a **start time and playback position** for the media. Each client plays from that shared clock, so screen and speaker line up without streaming frames or audio through the server.

## 7. Create: Aeronautics integration
- A **cable or brain can read data from the blocks next to it**, including **all** Aeronautics instrument blocks, such as the **altimeter** (how high) and the **speedometer** (how fast).
- Check the Aeronautics source for the real block names and what data each exposes, so the API matches what actually exists.

## 8. Security: Cipher and hacking
- A **Cipher** locks a system to its owner.
- Another player can try to **hack** the system with a laptop via a **mini game**.
- **Hack succeeds:** the hacker gets in and **keeps the Cipher**.
- **Hack fails:** the Cipher **breaks**.

**The mini game (skill check)**
- A **circle** with a **needle** sweeping around its outer edge.
- A **zone** of **random size** sits at a **random spot** on the circle.
- The player presses a key while the needle is **inside the zone** to hit it.

**Number of rounds**
- **3 rounds per 200 lines of code**, counting **all files** in the system (multiple files add up).
- So bigger programs are harder to hack. Example: 200 lines = 3 rounds, 400 lines = 6 rounds.
- **Rounding (decided):** round **up** per started 200 lines, minimum **3 rounds**. So 250 lines = 6 rounds.
- **Blank lines don't count**; comment lines do.
- **Max 30 rounds** (configurable), so a huge program isn't impossible to hack.
- The number of rounds is fixed when the hack starts.

**Failing and progress**
- **Missing** a round is a fail, and the Cipher **breaks**.
- You **only lose the part you lost**: hacking **progress is kept**. Example: a hack with 14 parts that fails at part 5 can be **continued from part 5** (with another Cipher).
- **Alert:** while the system is being hacked, **it stops working**. That is how the owner notices.

**Needle speed**
- The needle **gets faster and faster over time** as the hack goes on.
- When you **lose or the hack resets**, the speed **restarts at speed 1**.
- Continuing from a saved part also starts at speed 1.
- **Decided:** speed **1** = one full circle in 2 seconds. It grows by **+0.1 per round**, capped at **speed 3** (one circle in about 0.67 s). Step and cap are configurable.

**Other details (all configurable)**
- **Zone size:** random between **12% and 30%** of the circle.
- **After a failed or abandoned hack** (the hacker misses, closes the laptop, or walks away), the system **starts working again**.
- **Saved progress** is kept for **24 hours** (real time), per hacker and system. It **resets** if the owner locks the system again with a new Cipher.
- **One hacker at a time** per system.
- **On success** the hacker gets access to the system and **keeps their Cipher**. The owner can lock it again with a new Cipher.

## 9. Programming
- Language: **JavaScript** (in-game), run by the Brain.
- Editor: opened through the laptop (design below).
- **Device IDs:** every device on a network has an ID made of its type, a colon and a number: `brain:1`, `screen:1`, `servo:1`, `speaker:1`, `redstone:1`, `gateway:1`. Code gets a device with `get("screen:1")` (decided by Ralfs), for example `get("screen:1").log("hello")`. Suggestion: IDs are assigned automatically, counting per type in the order the devices join the network, and stay the same afterwards.
- **Output (decided by Ralfs):** scripts are plain, standard JavaScript. `console.log(...)` prints in the **Console panel of the laptop editor** (the IDE console). Text on a **Screen** is written through the device object instead: `get("screen:1").log("hello")` (method name `.log` is my reading of his answer; he can rename it). The earlier `screen.console(...)` form is dropped.
- API (what scripts can call): servo angle/speed, screen drawing (text, pixels, images, video), speaker playback and screen sync, reading neighbor blocks (altimeter, speedometer, etc.), firing redstone links, events. **[exact function names still to design]**

**Laptop editor design (mockup approved by Ralfs)**
- **Look:** dark theme, with a clean look inspired by Applied Energistics 2 (beveled panels, a teal accent). The textures are our own, not copied.
- **Layout, VS Code style:**
  - **Left, Explorer:** the files and folders of the linked system, plus a drop zone for dragging in images, sound and video.
  - **Middle, Editor:** tabs, line numbers and syntax colors, with a **Console** strip underneath showing output per device.
  - **Right, Devices:** one card per connected block with its **3D model**, its **location** (x, y, z), and its **ID** at the bottom to use in code.
  - **Top bar:** which system is linked, Remote Gateway status (50 blocks), and Stop / Run buttons.
  - **Status bar:** language, line and column, file and line count, Cipher state, saved.
- The selected device card is highlighted, and its ID is highlighted where it appears in the code.
- **Suggestion:** clicking a device card inserts its ID at the cursor.

## 10. Limits and safety (approved, configurable in the server config)

**Script sandbox**
- Scripts can only call the API we expose (GraalJS `HostAccess.EXPLICIT`). **No Java classes, no file access, no network, no threads, no native code.**
- Each Brain runs in its **own isolated context**; one script can't touch another.

**CPU**
- A **time budget per Brain per tick** (suggested ~2 ms, hard cap ~5 ms) plus a statement/instruction limit.
- A script that runs over is **paused or stopped**, and the error is shown in the laptop. It can't lag the server.
- A **global per-tick budget** across all Brains, so many Brains can't add up to lag.

**Size and rate limits**
- Max **file size** and max **total size per Brain** (suggested 64 KB per file, 512 KB per Brain).
- Caps on **timers/events per Brain** and **queued messages**.
- **Rate limits** on Servo commands, Redstone Link updates, Speaker sounds and screen draw calls per tick.

**Multiplayer and permissions**
- **The server decides everything.** The client only sends requests; the server validates every one.
- Distance checks: placed laptop within normal reach of the block; remote laptop within **50 blocks** of the Remote Gateway.
- Owner/Cipher checks on every edit. **Ops can override** and can stop any Brain with a command.
- Hack attempts are **rate-limited** so the mini game can't be brute-forced.

**Media (screens and speakers)**
- **URLs are fetched client-side**, `http`/`https` only, with a size cap, and can be **turned off in config**. The server never downloads URLs.
- **Dragged-in files** are uploaded to the server in chunks with a size cap (suggested 4 MB per image, 8 MB per audio file, 32 MB per video), stored with the world, and sent to clients on demand. Can be **turned off or capped in config**.

**Power**
- FE use is checked on the server. A Servo or Speaker with no FE stops working.

## 11. Open questions
None right now. Ralfs left the remaining details to Claude; they are decided in section 8 and can be tuned in the config.

## 12. Not decided yet
- Crafting recipes (later)
- Final GUI and block textures (the layout and look are decided in section 9)
- Milestones are in section 13

## 13. Milestones (proposed)
1. **Project skeleton:** NeoForge 1.21.1 mod, registries, GraalJS set up (dependencies `org.graalvm.polyglot:polyglot` + `js-community`; the library also goes on `additionalRuntimeClasspath`). **Done: files written, not built yet.**
2. **Brain + JavaScript runtime** with the sandbox and CPU limits from section 10.
3. **Cables:** Data Cable, Power Cable, Power Data Cable, and the network graph with FE.
4. **Laptop + editor GUI + Link Transmitter.**
5. **Servo** and Create shaft integration.
6. **Redstone Link** (8 links, Create style).
7. **Screen:** multi-block 1x1 to 4x4, draw commands, client rendering.
8. **Speaker + media:** file drag-in, URLs, video decoder, screen/speaker sync.
9. **Remote Gateway + Remote Access upgrade.**
10. **Cipher + hacking mini game.**
11. **Aeronautics sensors:** read the instrument blocks next to a cable or brain.
12. **Crafting recipes, textures and polish.**
