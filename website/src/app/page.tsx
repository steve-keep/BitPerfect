import Image from "next/image";
import Link from "next/link";

export default function Home() {
  return (
    <div className="max-w-4xl mx-auto px-4 py-16">
      <div className="flex flex-col items-center text-center space-y-8 mb-16">
        <Image
          src="/BitPerfect/logo.jpg"
          alt="BitPerfect Logo"
          width={180}
          height={180}
          className="rounded-full shadow-[0_0_30px_rgba(0,230,118,0.3)] border-4 border-surface"
        />
        <h1 className="text-5xl font-extrabold tracking-tight">
          Welcome to <span className="text-primary">BitPerfect</span>
        </h1>
        <p className="text-xl text-muted max-w-2xl">
          The ultimate, high-fidelity, bit-perfect CD ripping diagnostic tool for Android. Because your ears deserve the absolute truth. 💿✨
        </p>

        <div className="flex gap-4">
          <Link href="/technical" className="px-6 py-3 bg-primary text-background font-bold rounded-full hover:bg-opacity-80 transition shadow-[0_0_15px_rgba(0,230,118,0.4)]">
            Explore the Tech
          </Link>
          <Link href="/faq" className="px-6 py-3 bg-surface-elevated text-foreground font-bold rounded-full hover:bg-surface transition border border-surface-elevated">
            How to Use
          </Link>
        </div>
      </div>

      <div className="grid md:grid-cols-2 gap-8">
        <div className="bg-surface p-8 rounded-2xl">
          <h2 className="text-2xl font-bold mb-4 flex items-center gap-2">
            <span>🛡️</span> Secure Ripping
          </h2>
          <p className="text-muted">
            We don&apos;t just read the disc. We read it, re-read it, bust the drive&apos;s cache, and use C2 error pointers. We extract the bits kicking and screaming.
          </p>
        </div>

        <div className="bg-surface p-8 rounded-2xl">
          <h2 className="text-2xl font-bold mb-4 flex items-center gap-2">
            <span>✅</span> AccurateRip Verified
          </h2>
          <p className="text-muted">
            We check your rip against the AccurateRip database. Both v1 and v2 checksums. Because trusting a single drive is so 1999.
          </p>
        </div>

        <div className="bg-surface p-8 rounded-2xl">
          <h2 className="text-2xl font-bold mb-4 flex items-center gap-2">
            <span>🎛️</span> Audiophile Grade
          </h2>
          <p className="text-muted">
            Offset correction? Yes. Gap detection? Absolutely. Proper FLAC tagging and EAC-compatible log files? We wouldn&apos;t have it any other way. 🎶
          </p>
        </div>

        <div className="bg-surface p-8 rounded-2xl">
          <h2 className="text-2xl font-bold mb-4 flex items-center gap-2">
            <span>🚀</span> Bit-Perfect Bypass
          </h2>
          <p className="text-muted">
            Android loves to resample your audio. We bypass the standard Android audio pipeline entirely to send pure 44.1kHz/16-bit PCM straight to your USB DAC.
          </p>
        </div>
      </div>

      <div className="mt-16 bg-surface-elevated p-10 rounded-3xl text-center border border-primary/20">
        <h2 className="text-3xl font-bold mb-6">Ready to see the magic? ✨</h2>
        <p className="text-muted mb-8">
          Dive into our philosophy, or read the deep-dive technical documentation on how we bend USB SCSI commands to our will.
        </p>
        <Link href="/philosophy" className="text-primary hover:underline font-bold text-lg">
          Read the Philosophy →
        </Link>
      </div>
    </div>
  );
}
