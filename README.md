# 🚚 BetterTradeConvoys – Dynamic NPC Trade Convoys

**BetterTradeConvoys** is a Minecraft plugin that replaces static trading with **dynamic NPC convoys**.  
Players can invest items into a Citizens NPC, who will **walk a real route** through the world and return with rewards – if they survive.  
This introduces **risk, PvP opportunities, and a player-driven economy layer**.

If you have any questions or need help, feel free to join my Discord: https://discord.gg/gBEKWGanM7

---

### ✨ Features

- 🚶 **Dynamic Trade Routes**  
  - NPCs walk along **configured waypoints** (`routes.yml`).  
  - Special `trade`-stops make the NPC wait for a set duration.  
  - If the NPC reaches the last waypoint, the owner can **claim rewards**.

- ⚔️ **PvP & Risk**  
  - NPCs can be attacked by anyone.  
  - On death, they **drop the carried items**.  
  - Owners must protect their convoy if they want the rewards.

- ⏳ **Limits & Cooldowns**  
  - Configure **daily, weekly, and monthly limits** per route.  
  - Route-specific **cooldowns** for each player.  

- 🛠 **Flexible Configuration**  
  - Define NPCs by **Citizens NPC-IDs**.  
  - Control movement speed, follow-radius, and expiration timers.  
  - Enable/disable global start announcements.  
  - Define waypoints and routes via coordinates.

- 📦 **Trade System**  
  - Define **input → output trades** per route.  
  - Supports **custom items** from other plugins (Oraxen, MMOItems, etc.) via API hooks.  

- 🔒 **Persistent Data**  
  - Progress, cooldowns, and claims survive restarts.  
  - If the server stops mid-run, convoys fail gracefully.  

- 🏳️ **Language Customization**  
  - All messages configurable in `language.yml`.  

---

### ⚙️ Configuration

Example `routes.yml`:

```yaml
routes:
  iron_to_diamond:
    display-name: "Iron → Diamond Run"
    npc-ids: [1234, 2343]
    waypoint-world: "world"

    speed: 1.15
    follow-radius: 24
    expire-seconds: 900
    announce-start: true
    trade-delay-seconds: 8

    steps:
      - { x: 100.5, y: 64.0, z: -30.5 }
      - { x: 120.5, y: 64.0, z: -30.5 }
      - trade
      - { x: 140.5, y: 64.0, z: -15.5 }

    trades:
      - input:  { material: IRON_INGOT, amount: 20 }
        output: { material: DIAMOND,    amount: 10 }

    daily-limit: 3
    weekly-limit: 10
    monthly-limit: 30
    cooldown-seconds: 3600
```

---

### 🧩 Requirements

- Minecraft (Paper/Spigot) `1.20+`  
- Citizens `2.0.39+` build matching your server version  
- Java `21` 

---

### 🚀 Installation

1. Install [Citizens](https://ci.citizensnpcs.co/job/Citizens2/) matching your server version.  
2. Download the latest `BetterTradeConvoys-x.x.jar`.  
3. Place it into your server’s `plugins/` folder.  
4. Restart the server to generate configs (`routes.yml`, `language.yml`).  
5. Configure your routes and NPC IDs.  

---

### 📌 Roadmap Ideas

- [ ] Integration with Vault economy for trade costs/rewards  
