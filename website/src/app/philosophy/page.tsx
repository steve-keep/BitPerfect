export default function Philosophy() {
  return (
    <div className="max-w-4xl mx-auto px-4 py-16">
      <div className="text-center mb-16">
        <h1 className="text-5xl font-extrabold mb-6">Our Philosophy 🧘‍♂️🎶</h1>
        <p className="text-xl text-primary font-medium">Because &quot;good enough&quot; audio isn&apos;t good enough.</p>
      </div>

      <div className="space-y-12">
        <section className="bg-surface p-8 rounded-2xl border-l-4 border-primary">
          <h2 className="text-3xl font-bold mb-4">The Problem with Android Audio</h2>
          <p className="text-muted leading-relaxed text-lg">
            Android was designed for smartphones. It needs to play your ringtone, your podcast, and your game sound effects all at the same time. To do this, the Android OS (specifically AudioFlinger) mixes all these audio streams together.
          </p>
          <p className="text-muted leading-relaxed text-lg mt-4">
            The catch? To mix them, it forces them all to share the same sample rate—usually 48kHz. If you play a standard CD-quality 44.1kHz FLAC file, Android mathematically recalculates (resamples) the audio to fit 48kHz. <strong>This permanently alters the original bits.</strong> For an audiophile, this is unacceptable.
          </p>
        </section>

        <section className="bg-surface p-8 rounded-2xl border-l-4 border-primary">
          <h2 className="text-3xl font-bold mb-4">The BitPerfect Bypass</h2>
          <p className="text-muted leading-relaxed text-lg">
            BitPerfect was built from the ground up to tell Android&apos;s audio mixer to step aside. We use custom C++ code and libusb to open a direct, exclusive channel to your external USB DAC.
          </p>
          <p className="text-muted leading-relaxed text-lg mt-4">
            We send exactly 44,100 samples every second. No mixing. No volume normalization. No EQ. Just the pure, unadulterated PCM data exactly as it was mastered on the compact disc.
          </p>
        </section>

        <section className="bg-surface p-8 rounded-2xl border-l-4 border-primary">
          <h2 className="text-3xl font-bold mb-4">Trust, but Verify (AccurateRip)</h2>
          <p className="text-muted leading-relaxed text-lg">
            How do you know your CD rip is flawless? You don&apos;t just trust your drive. BitPerfect calculates a cryptographic checksum of the audio data and queries the global AccurateRip database.
          </p>
          <p className="text-muted leading-relaxed text-lg mt-4">
            If your checksum matches what 50 other people around the world got when ripping that exact same pressing, you can be 100% mathematically certain your rip is perfect. That&apos;s the confidence we deliver.
          </p>
        </section>

        <section className="bg-surface p-8 rounded-2xl border-l-4 border-primary">
          <h2 className="text-3xl font-bold mb-4">The &quot;No-Line&quot; Design System</h2>
          <p className="text-muted leading-relaxed text-lg">
            Even our UI has a philosophy. We call it &quot;The High-Fidelity Diagnostic.&quot; We avoid rigid 1px border lines in favor of tonal surface shifts. The app is designed to look like a piece of high-end diagnostic lab equipment, not a flashy music player. Dark themes, high-contrast greens, and a focus on terminal-level truth.
          </p>
        </section>
      </div>
    </div>
  );
}
