import Mermaid from "../../components/Mermaid";

export default function Technical() {
  const appArchitecture = `
graph TB
    subgraph Android App
        APP[":app<br/>UI / Compose"]
        CORE[":core<br/>Domain / Use Cases"]
        DRIVER[":driver<br/>USB HAL / C++ / JNI"]
    end

    APP <--> CORE
    CORE <--> DRIVER
  `;

  const rippingFlow = `
flowchart TD
    A["User taps &apos;Rip CD&apos;"] --> B["UsbDeviceMonitor\nDrive connected + permission granted"]
    B --> C["INQUIRY → TEST UNIT READY → GET CONFIGURATION\n→ DriveCapabilities"]
    C --> D["READ TOC\n→ List of CdTrack with LBA ranges"]
    D --> E["Background coroutines\nFetch AccurateRip binary + MusicBrainz metadata"]
    E --> F["For each audio track T"]
    F --> G["Multi-pass secure read + cache-bust + offset correction"]
  `;

  const playbackPipeline = `
flowchart TD
    A["FlacDecoder\n(libFLAC / JNI)\n16-bit PCM @ 44100 Hz"] --> B["AudioRingBuffer\nLock-free C++ ring buffer\n~500ms pre-fill"]
    B --> C["IsochronousScheduler\nC++ thread @ PRIORITY_URGENT_AUDIO\n1ms USB isochronous packets"]
    C --> D["libusb_submit_transfer()\nUSB OUT endpoint"]
    D --> E["USB DAC 🎵\n44.1kHz / 16-bit"]
  `;

  return (
    <div className="max-w-4xl mx-auto px-4 py-16">
      <div className="mb-12">
        <h1 className="text-4xl font-extrabold mb-4 border-b-4 border-primary pb-2 inline-block">Technical Documentation 🛠️</h1>
        <p className="text-muted text-lg mt-4">
          Under the hood of BitPerfect. We talk directly to the hardware using SCSI MMC commands over USB BOT (Bulk-Only Transport).
        </p>
      </div>

      <section className="mb-16">
        <h2 className="text-3xl font-bold mb-6 text-primary">System Architecture</h2>
        <div className="bg-surface p-6 rounded-xl text-muted leading-relaxed mb-6">
          <p>
            BitPerfect is modularized into three main components: The UI layer (Jetpack Compose), the Core Domain layer, and the Driver layer (C++ / JNI / libusb). This clean architecture ensures the complex async ripping logic is decoupled from the UI state machine.
          </p>
        </div>
        <Mermaid chart={appArchitecture} />
      </section>

      <section className="mb-16">
        <h2 className="text-3xl font-bold mb-6 text-primary">The Secure Ripping Flow</h2>
        <div className="bg-surface p-6 rounded-xl text-muted leading-relaxed mb-6 space-y-4">
          <p>
            Standard burst ripping just issues READ commands and hopes for the best. BitPerfect uses a rigorous multi-pass approach.
          </p>
          <ul className="list-disc pl-5 space-y-2">
            <li><strong>Discovery:</strong> INQUIRY (0x12) and GET CONFIGURATION (0x46) to determine C2 and AccurateStream support.</li>
            <li><strong>Cache Busting:</strong> We issue a decoy read to a distant sector to flush the drive&apos;s hardware cache before re-reading.</li>
            <li><strong>Offset Correction:</strong> Read offsets are detected via the AccurateRip DB or manually configured, and PCM data is shifted at the byte level.</li>
          </ul>
        </div>
        <Mermaid chart={rippingFlow} />
      </section>

      <section className="mb-16">
        <h2 className="text-3xl font-bold mb-6 text-primary">Bit-Perfect Playback Pipeline</h2>
        <div className="bg-surface p-6 rounded-xl text-muted leading-relaxed mb-6 space-y-4">
          <p>
            Android&apos;s default <code>AudioTrack</code> usually resamples audio to 48kHz, destroying bit-perfect fidelity.
          </p>
          <p>
            BitPerfect implements a custom playback pipeline. On supported devices, we use Android 14+ <code>MIXER_BEHAVIOR_BIT_PERFECT</code>. On others, we fall back to a hardcore <strong>libusb isochronous pipeline</strong> that bypasses Android&apos;s audio system entirely.
          </p>
        </div>
        <Mermaid chart={playbackPipeline} />
      </section>

    </div>
  );
}
