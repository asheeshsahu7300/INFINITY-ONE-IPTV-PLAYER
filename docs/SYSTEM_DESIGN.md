# INFINITY ONE: System Design & High-Level Design (HLD)

This document provides a comprehensive technical overview of the system architecture, component boundaries, data pipelines, and design trade-offs of **INFINITY ONE** — a native IPTV client engineered for Android TV, Google TV, Android tablets, and mobile devices.

---

## 1. System Context & Requirements

### 1.1 Operating Environment & Constraints
- **Zero Centralized Backend**: The client communicates directly with user-supplied streaming servers (Xtream Codes API, Ministra/Stalker MAG portals, M3U/M3U8 playlists, XMLTV EPG endpoints).
- **Edge Embedded Hardware**:
  - Target Devices: Fire TV Stick, Google TV Chromecast, Android TV set-top boxes.
  - Resource Bounds: $1.0\text{ GB} - 2.0\text{ GB}$ system RAM, low-power quad-core ARM SoCs, limited GPU bandwidth.
- **Latency Budgets**:
  - Channel switching: $< 1.2\text{ s}$ to initial video render.
  - UI Framerate: Constant $60\text{ fps}$ ($\le 16.6\text{ ms}$ frame budget) under rapid remote navigation.

---

## 2. High-Level Architecture

The system uses a unidirectional, data-driven architecture divided into five core tiers:

```mermaid
flowchart TD
    subgraph Presentation_Tier["1. Presentation Tier (Jetpack Compose)"]
        UI_Home["Home & Categorized Catalog"]
        UI_Player["Player & Dynamic OSD Controls"]
        UI_Search["Search Screen (Debounced & Throttled)"]
        UI_Detail["Detail View (VOD & Series Metadata)"]
    end

    subgraph State_Coordination["2. State & Coordination Tier"]
        NavRouter["Focus & Navigation Router"]
        Store["Global In-Memory Singletons (Store, PlaylistStore)"]
        SearchIdx["Inverted Search Index & Word Suggester"]
        PhoneServer["Embedded Companion HTTP Server (:8765)"]
    end

    subgraph Ingestion_Protocols["3. Protocol & Ingestion Tier"]
        StalkerEngine["Stalker / MAG Portal Engine (Token, Lock, Profile)"]
        XtreamEngine["Xtream Codes REST Adapter"]
        M3uParser["Streaming Fast M3U / M3U8 Parser"]
        EpgEngine["GZipped XMLTV EPG Ingestor"]
    end

    subgraph Metadata_Tier["4. Metadata & Intelligence Tier"]
        MetaRepo["2-Tier Cache Engine (L1 LRU + L2 Slim Disk Index)"]
        MatchLogic["Fuzzy Match & Normalization Engine"]
        OmdbTmdb["External TMDB / OMDb Fallback Clients"]
    end

    subgraph Playback_Engine["5. Playback & Codec Tier"]
        Media3Core["Google Media3 / ExoPlayer Engine"]
        TrackSelector["Adaptive Audio & Subtitle Track Selector"]
        SoftAudio["Software Audio Decoder (FFmpeg Reflection)"]
        TimeshiftBuf["Timeshift Ring Buffer & Recorder"]
    end

    Presentation_Tier --> State_Coordination
    State_Coordination --> Ingestion_Protocols
    State_Coordination --> Metadata_Tier
    State_Coordination --> Playback_Engine

    Metadata_Tier <--> Ingestion_Protocols
    Playback_Engine <--> Ingestion_Protocols
```

---

## 3. Component Deep Dive

### 3.1 Protocol Ingestion Engines
1. **Stalker/Ministra MAG Protocol**:
   - Implements authentication handshakes (`type=stb&action=handshake`), device profile verification (`get_profile`), and token-authenticated stream link creation (`type=itv/vod&action=create_link`).
   - Serializes concurrent HTTP operations through a thread-safe `ReentrantLock` (`callLock`) to prevent server bans.
   - Supports priority request preemption (`yieldToUrgent()`) so channel changes preempt long-running background catalog syncs.

2. **Xtream Codes Protocol**:
   - Interfaces with `player_api.php` endpoints for category trees, VOD movie streams, and series episode structures.
   - Manages live stream token appending and automatic 404 session recovery.

3. **M3U / Streaming Tokenizer**:
   - Performs streaming line-by-line parsing of massive playlists ($100{,}000+$ items) without loading full files into memory, keeping heap allocation minimal.

---

### 3.2 Media & Playback Subsystem
- **ExoPlayer Configuration**:
  - Buffer bounds: $\text{minBuffer} = 15\text{ s}$, $\text{maxBuffer} = 50\text{ s}$, $\text{playbackBuffer} = 1.5\text{ s}$.
  - Custom `HttpDataSource` with custom player User-Agents (`VLC/3.0.18`, standard MAG stbapp) to prevent server stream rejection.
- **Software Codec Reflection (`SoftAudio`)**:
  - Dynamically loads `androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer` to support AC3/EAC3/DTS audio streams on hardware missing proprietary codec licenses.

---

### 3.3 Metadata & Caching Architecture
- **L1 In-Memory Cache**: LRU map holding 400 complete `Meta` objects.
- **L2 Disk Cache (`_slim.idx`)**: Compact tab-delimited JSON index for instant access to cover URLs, years, and ratings across entire catalogs.
- **Safe Matching Logic**: Eliminates blind first-item fallbacks to guarantee posters and synopsis details match queried titles.

---

## 4. End-to-End Sequence Workflows

### 4.1 Channel Zapping Workflow

```mermaid
sequenceDiagram
    autonumber
    participant Remote as D-Pad Remote
    participant UI as PlayerScreen
    participant Stalker as StalkerApi
    participant Portal as MAG Portal
    participant Exo as ExoPlayer

    Remote->>UI: Key UP/DOWN (Switch Channel)
    UI->>Stalker: play(channel)
    Stalker->>Portal: action=create_link&cmd=ffmpeg...
    Portal-->>Stalker: Playable Stream URL
    Stalker-->>UI: Final Playback URL
    UI->>Exo: setMediaItem(url)
    Exo->>Portal: Stream Request (HLS / MPEG-TS)
    Portal-->>Exo: Video Stream Data
    Exo-->>UI: First Frame Rendered (Zap Time < 1.2s)
```
